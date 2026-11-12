package com.stockguard.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test suite verifying the approved Milestone 3 V3 authentication
 * and session management schema, constraints, partial unique index, triggers, and runtime privileges.
 * <p>
 * Structurally separated into four independent test groups:
 * 1. Part A: Active Refresh-Token Uniqueness & Atomic Rotation Tests (6 Cases)
 * 2. Part B: Runtime Role Privilege Boundary Tests (Role: stockguard_app, 5 Cases)
 * 3. Part C: Database Trigger & Constraint Integrity Tests (Role: stockguard_test_privileged, 6 Cases)
 * 4. Part D: Cascading Complete Session Cleanup Tests (Role: stockguard_test_privileged, 2 Cases)
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class RefreshTokenLineageIntegrityTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("stockguard_test")
            .withUsername("test_user")
            .withPassword("test_pass")
            .withInitScript("init-test-roles.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private JdbcTemplate appJdbcTemplate;
    private JdbcTemplate privilegedJdbcTemplate;

    @BeforeEach
    void setupRoleTemplates() {
        // Table privileges for the non-owner trigger test role (stockguard_test_privileged).
        // stockguard_app receives ZERO manual grants here: its permissions are 100% applied
        // by the conditional block in Flyway migration V3__create_auth_and_session_tables.sql!
        jdbcTemplate.execute("""
            GRANT SELECT, INSERT, UPDATE, DELETE ON refresh_tokens, auth_sessions, users TO stockguard_test_privileged;
        """);

        DataSource appDataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), "stockguard_app", "test_app_pass");
        this.appJdbcTemplate = new JdbcTemplate(appDataSource);

        DataSource privDataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), "stockguard_test_privileged", "test_priv_pass");
        this.privilegedJdbcTemplate = new JdbcTemplate(privDataSource);
    }

    // ========================================================================
    // Fixture Helpers
    // ========================================================================

    private UUID createTestUser(String emailPrefix) {
        UUID userId = UUID.randomUUID();
        String email = emailPrefix.toLowerCase() + "-" + userId + "@example.com";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, normalized_email, active) VALUES (?, ?, ?, TRUE)",
                userId, email, email
        );
        return userId;
    }

    private UUID createTestSession(UUID userId) {
        UUID sessionId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(30, ChronoUnit.DAYS);
        jdbcTemplate.update(
                "INSERT INTO auth_sessions (id, user_id, revoked, expires_at, created_at, updated_at) " +
                "VALUES (?, ?, FALSE, ?, ?, ?)",
                sessionId, userId, Timestamp.from(expiresAt), Timestamp.from(now), Timestamp.from(now)
        );
        return sessionId;
    }

    private String sampleHash(String seed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest((seed + "-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : digest) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void assertSqlException(Runnable runnable, String expectedSqlState, String expectedConstraintOrText) {
        assertThatThrownBy(runnable::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(ex -> {
                    Throwable rootCause = NestedExceptionUtils.getRootCause(ex);
                    assertThat(rootCause).isInstanceOf(PSQLException.class);
                    PSQLException psqlEx = (PSQLException) rootCause;
                    if (expectedSqlState != null) {
                        assertThat(psqlEx.getSQLState()).isEqualTo(expectedSqlState);
                    }
                    if (expectedConstraintOrText != null) {
                        assertThat(psqlEx.getMessage()).contains(expectedConstraintOrText);
                    }
                });
    }

    // ========================================================================
    // Part A: Active Refresh-Token Uniqueness & Atomic Rotation Tests (6 Cases)
    // ========================================================================

    @Nested
    @DisplayName("Part A: Active Token Uniqueness & Atomic Rotation Tests")
    class ActiveTokenUniquenessTests {

        @Test
        @DisplayName("Case 1: Insert one active token for a session succeeds")
        void insertOneActiveTokenSucceeds() {
            UUID userId = createTestUser("parta-c1");
            UUID sessionId = createTestSession(userId);
            UUID tokenId = UUID.randomUUID();
            String hash = sampleHash("c1");
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            int inserted = jdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, ?)",
                    tokenId, sessionId, hash, Timestamp.from(expiresAt)
            );

            assertThat(inserted).isEqualTo(1);
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM refresh_tokens WHERE session_id = ? AND consumed_at IS NULL",
                    Integer.class, sessionId
            );
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("Case 2: Insert duplicate active token in same session is rejected with unique violation")
        void insertDuplicateActiveTokenRejected() {
            UUID userId = createTestUser("parta-c2");
            UUID sessionId = createTestSession(userId);
            UUID t1Id = UUID.randomUUID();
            UUID t2Id = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            // Insert T1 in active state
            jdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, ?)",
                    t1Id, sessionId, sampleHash("c2-1"), Timestamp.from(expiresAt)
            );

            // Attempt to insert T2 in active state for same session
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                            "VALUES (?, ?, ?, 2, ?)",
                            t2Id, sessionId, sampleHash("c2-2"), Timestamp.from(expiresAt)
                    ),
                    "23505",
                    "uq_refresh_tokens_one_active_per_session"
            );
        }

        @Test
        @DisplayName("Case 3: Insert active token for distinct session succeeds")
        void insertActiveTokenDistinctSessionSucceeds() {
            UUID userId = createTestUser("parta-c3");
            UUID s1Id = createTestSession(userId);
            UUID s2Id = createTestSession(userId);
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            int ins1 = jdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, ?)",
                    UUID.randomUUID(), s1Id, sampleHash("c3-1"), Timestamp.from(expiresAt)
            );
            int ins2 = jdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, ?)",
                    UUID.randomUUID(), s2Id, sampleHash("c3-2"), Timestamp.from(expiresAt)
            );

            assertThat(ins1).isEqualTo(1);
            assertThat(ins2).isEqualTo(1);
        }

        @Test
        @DisplayName("Case 4: Rotate an active token and insert its successor atomically succeeds")
        void atomicRotationSucceeds() {
            UUID userId = createTestUser("parta-c4");
            UUID sessionId = createTestSession(userId);
            UUID t1Id = UUID.randomUUID();
            UUID t2Id = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            jdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, ?)",
                    t1Id, sessionId, sampleHash("c4-1"), Timestamp.from(expiresAt)
            );

            TransactionTemplate tx = new TransactionTemplate(
                    new DataSourceTransactionManager(jdbcTemplate.getDataSource())
            );

            tx.executeWithoutResult(status -> {
                // 1. In a single transaction: transition T1 to consumed pointing to T2 (FK check is deferred to commit)
                int updated = jdbcTemplate.update(
                        "UPDATE refresh_tokens SET consumed_at = CURRENT_TIMESTAMP, replaced_by_token_id = ? WHERE id = ?",
                        t2Id, t1Id
                );
                assertThat(updated).isEqualTo(1);

                // 2. Insert successor T2 (now the sole active token for the session)
                jdbcTemplate.update(
                        "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                        "VALUES (?, ?, ?, 2, ?)",
                        t2Id, sessionId, sampleHash("c4-2"), Timestamp.from(expiresAt)
                );
            });

            // Assert final state: T1 is consumed, T2 is sole active
            Integer activeCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM refresh_tokens WHERE session_id = ? AND consumed_at IS NULL",
                    Integer.class, sessionId
            );
            assertThat(activeCount).isEqualTo(1);

            UUID activeId = jdbcTemplate.queryForObject(
                    "SELECT id FROM refresh_tokens WHERE session_id = ? AND consumed_at IS NULL",
                    UUID.class, sessionId
            );
            assertThat(activeId).isEqualTo(t2Id);
        }

        @Test
        @DisplayName("Case 5: Concurrent successor issuance race condition allows at most one transaction to succeed")
        void concurrentSuccessorIssuanceAtMostOneSucceeds() throws Exception {
            UUID userId = createTestUser("parta-c5");
            UUID sessionId = createTestSession(userId);
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            int numThreads = 4;
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            CountDownLatch readyLatch = new CountDownLatch(numThreads);
            CountDownLatch startLatch = new CountDownLatch(1);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger conflictCount = new AtomicInteger(0);

            for (int i = 0; i < numThreads; i++) {
                final int seq = i + 1;
                executor.submit(() -> {
                    readyLatch.countDown();
                    try {
                        startLatch.await();
                        jdbcTemplate.update(
                                "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                                "VALUES (?, ?, ?, ?, ?)",
                                UUID.randomUUID(), sessionId, sampleHash("c5-" + seq), seq, Timestamp.from(expiresAt)
                        );
                        successCount.incrementAndGet();
                    } catch (Exception ex) {
                        Throwable root = NestedExceptionUtils.getRootCause(ex);
                        if (root instanceof PSQLException psql && "23505".equals(psql.getSQLState())) {
                            conflictCount.incrementAndGet();
                        }
                    }
                });
            }

            readyLatch.await(5, TimeUnit.SECONDS);
            startLatch.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            // Exactly ONE thread succeeded in inserting an active token; remaining threads failed with 23505
            assertThat(successCount.get()).isEqualTo(1);
            assertThat(conflictCount.get()).isEqualTo(numThreads - 1);
        }

        @Test
        @DisplayName("Case 6: Verify no session can have more than one active token after operations")
        void zeroDuplicateActiveTokensInvariant() {
            Integer violationCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM (" +
                    "  SELECT session_id FROM refresh_tokens WHERE consumed_at IS NULL " +
                    "  GROUP BY session_id HAVING COUNT(*) > 1" +
                    ") v",
                    Integer.class
            );
            assertThat(violationCount).isEqualTo(0);
        }
    }

    // ========================================================================
    // Part B: Runtime Role Privilege Boundary Tests (Role: stockguard_app) (5 Cases)
    // ========================================================================

    @Nested
    @DisplayName("Part B: Runtime Role Privilege Boundary Tests (Role: stockguard_app)")
    class RuntimeRolePrivilegeTests {

        @Test
        @DisplayName("Case 7: stockguard_app cannot DELETE refresh_tokens (SQLSTATE 42501) and has secure role bounds")
        void stockguardAppCannotDeleteRefreshTokens() {
            UUID userId = createTestUser("partb-c7");
            UUID sessionId = createTestSession(userId);
            UUID tokenId = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            jdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, ?)",
                    tokenId, sessionId, sampleHash("b7"), Timestamp.from(expiresAt)
            );

            // 1. Attempt DELETE as stockguard_app -> Must fail at privilege boundary before trigger
            assertSqlException(
                    () -> appJdbcTemplate.update("DELETE FROM refresh_tokens WHERE id = ?", tokenId),
                    "42501",
                    "permission denied for table refresh_tokens"
            );

            // 2. Verify stockguard_app cannot SET ROLE to stockguard_owner or test_user
            assertSqlException(
                    () -> appJdbcTemplate.execute("SET ROLE stockguard_owner"),
                    "42501",
                    "permission denied to set role"
            );
            assertSqlException(
                    () -> appJdbcTemplate.execute("SET ROLE test_user"),
                    "42501",
                    "permission denied to set role"
            );

            // 3. Catalog checks: verify no membership in owner or superuser roles
            Boolean memberOfOwner = jdbcTemplate.queryForObject(
                    "SELECT pg_has_role('stockguard_app', 'stockguard_owner', 'MEMBER')", Boolean.class);
            assertThat(memberOfOwner).isFalse();

            Boolean memberOfTestUser = jdbcTemplate.queryForObject(
                    "SELECT pg_has_role('stockguard_app', 'test_user', 'MEMBER')", Boolean.class);
            assertThat(memberOfTestUser).isFalse();

            // 4. Verify role attributes: NOSUPERUSER, NOCREATEDB, NOCREATEROLE, NOINHERIT
            Boolean isSuper = jdbcTemplate.queryForObject(
                    "SELECT rolsuper FROM pg_roles WHERE rolname = 'stockguard_app'", Boolean.class);
            assertThat(isSuper).isFalse();

            Boolean canCreateDb = jdbcTemplate.queryForObject(
                    "SELECT rolcreatedb FROM pg_roles WHERE rolname = 'stockguard_app'", Boolean.class);
            assertThat(canCreateDb).isFalse();

            Boolean canCreateRole = jdbcTemplate.queryForObject(
                    "SELECT rolcreaterole FROM pg_roles WHERE rolname = 'stockguard_app'", Boolean.class);
            assertThat(canCreateRole).isFalse();

            Boolean canInherit = jdbcTemplate.queryForObject(
                    "SELECT rolinherit FROM pg_roles WHERE rolname = 'stockguard_app'", Boolean.class);
            assertThat(canInherit).isFalse();

            // 5. Table ownership verification: neither table is owned by stockguard_app or stockguard_test_privileged
            String authSessionsOwner = jdbcTemplate.queryForObject(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'auth_sessions'", String.class);
            assertThat(authSessionsOwner)
                    .isNotEqualTo("stockguard_app")
                    .isNotEqualTo("stockguard_test_privileged")
                    .isIn("stockguard_owner", "test_user");

            String refreshTokensOwner = jdbcTemplate.queryForObject(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'refresh_tokens'", String.class);
            assertThat(refreshTokensOwner)
                    .isNotEqualTo("stockguard_app")
                    .isNotEqualTo("stockguard_test_privileged")
                    .isIn("stockguard_owner", "test_user");
        }

        @Test
        @DisplayName("Case 8: stockguard_app cannot update token_hash (SQLSTATE 42501)")
        void stockguardAppCannotUpdateTokenHash() {
            UUID userId = createTestUser("partb-c8");
            UUID sessionId = createTestSession(userId);
            UUID tokenId = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            jdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, ?)",
                    tokenId, sessionId, sampleHash("b8"), Timestamp.from(expiresAt)
            );

            assertSqlException(
                    () -> appJdbcTemplate.update("UPDATE refresh_tokens SET token_hash = ? WHERE id = ?",
                            sampleHash("b8-tamper"), tokenId),
                    "42501",
                    "permission denied"
            );
        }

        @Test
        @DisplayName("Case 9: stockguard_app cannot update session_id (SQLSTATE 42501)")
        void stockguardAppCannotUpdateSessionId() {
            UUID userId = createTestUser("partb-c9");
            UUID sessionId = createTestSession(userId);
            UUID tokenId = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            jdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, ?)",
                    tokenId, sessionId, sampleHash("b9"), Timestamp.from(expiresAt)
            );

            assertSqlException(
                    () -> appJdbcTemplate.update("UPDATE refresh_tokens SET session_id = ? WHERE id = ?",
                            UUID.randomUUID(), tokenId),
                    "42501",
                    "permission denied"
            );
        }

        @Test
        @DisplayName("Case 10: stockguard_app cannot update sequence_number (SQLSTATE 42501)")
        void stockguardAppCannotUpdateSequenceNumber() {
            UUID userId = createTestUser("partb-c10");
            UUID sessionId = createTestSession(userId);
            UUID tokenId = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            jdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, ?)",
                    tokenId, sessionId, sampleHash("b10"), Timestamp.from(expiresAt)
            );

            assertSqlException(
                    () -> appJdbcTemplate.update("UPDATE refresh_tokens SET sequence_number = 999 WHERE id = ?",
                            tokenId),
                    "42501",
                    "permission denied"
            );
        }

        @Test
        @DisplayName("Case 11: stockguard_app can perform the permitted one-way transition and grants originate from Flyway V3")
        void stockguardAppCanPerformPermittedTransition() {
            // 1. Verify V3 Flyway migration grants were applied directly without manual re-creation
            List<String> tablePrivs = jdbcTemplate.queryForList(
                    "SELECT privilege_type FROM information_schema.table_privileges " +
                    "WHERE grantee = 'stockguard_app' AND table_name = 'refresh_tokens'",
                    String.class
            );
            assertThat(tablePrivs).containsExactlyInAnyOrder("SELECT", "INSERT");

            List<String> colPrivs = jdbcTemplate.queryForList(
                    "SELECT column_name FROM information_schema.column_privileges " +
                    "WHERE grantee = 'stockguard_app' AND table_name = 'refresh_tokens' AND privilege_type = 'UPDATE'",
                    String.class
            );
            assertThat(colPrivs).containsExactlyInAnyOrder("consumed_at", "replaced_by_token_id");

            // 2. Perform rotation using stockguard_app
            UUID userId = createTestUser("partb-c11");
            UUID sessionId = createTestSession(userId);
            UUID t1Id = UUID.randomUUID();
            UUID t2Id = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            int ins1 = appJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, ?)",
                    t1Id, sessionId, sampleHash("b11-1"), Timestamp.from(expiresAt)
            );
            assertThat(ins1).isEqualTo(1);

            TransactionTemplate tx = new TransactionTemplate(
                    new DataSourceTransactionManager(appJdbcTemplate.getDataSource())
            );

            tx.executeWithoutResult(status -> {
                int updated = appJdbcTemplate.update(
                        "UPDATE refresh_tokens SET consumed_at = CURRENT_TIMESTAMP, replaced_by_token_id = ? WHERE id = ?",
                        t2Id, t1Id
                );
                assertThat(updated).isEqualTo(1);

                appJdbcTemplate.update(
                        "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                        "VALUES (?, ?, ?, 2, ?)",
                        t2Id, sessionId, sampleHash("b11-2"), Timestamp.from(expiresAt)
                );
            });

            Timestamp consumed = jdbcTemplate.queryForObject(
                    "SELECT consumed_at FROM refresh_tokens WHERE id = ?",
                    Timestamp.class, t1Id
            );
            assertThat(consumed).isNotNull();
        }
    }

    // ========================================================================
    // Part C: Database Trigger & Constraint Integrity Tests (Role: stockguard_test_privileged) (6 Cases)
    // ========================================================================

    @Nested
    @DisplayName("Part C: Database Trigger & Constraint Integrity Tests (Role: stockguard_test_privileged)")
    class DatabaseTriggerIntegrityTests {

        @Test
        @DisplayName("Case 12: Direct deletion of root token is rejected by trigger while session exists (SQLSTATE 55000) and role bounds verified")
        void directDeletionOfRootTokenRejectedByTrigger() {
            // 1. Role escalation & ownership verification for stockguard_test_privileged
            assertSqlException(
                    () -> privilegedJdbcTemplate.execute("SET ROLE stockguard_owner"),
                    "42501",
                    "permission denied to set role"
            );
            assertSqlException(
                    () -> privilegedJdbcTemplate.execute("SET ROLE test_user"),
                    "42501",
                    "permission denied to set role"
            );

            Boolean memberOfOwner = jdbcTemplate.queryForObject(
                    "SELECT pg_has_role('stockguard_test_privileged', 'stockguard_owner', 'MEMBER')", Boolean.class);
            assertThat(memberOfOwner).isFalse();

            Boolean memberOfTestUser = jdbcTemplate.queryForObject(
                    "SELECT pg_has_role('stockguard_test_privileged', 'test_user', 'MEMBER')", Boolean.class);
            assertThat(memberOfTestUser).isFalse();

            Boolean isSuper = jdbcTemplate.queryForObject(
                    "SELECT rolsuper FROM pg_roles WHERE rolname = 'stockguard_test_privileged'", Boolean.class);
            assertThat(isSuper).isFalse();

            Boolean canCreateDb = jdbcTemplate.queryForObject(
                    "SELECT rolcreatedb FROM pg_roles WHERE rolname = 'stockguard_test_privileged'", Boolean.class);
            assertThat(canCreateDb).isFalse();

            Boolean canCreateRole = jdbcTemplate.queryForObject(
                    "SELECT rolcreaterole FROM pg_roles WHERE rolname = 'stockguard_test_privileged'", Boolean.class);
            assertThat(canCreateRole).isFalse();

            Boolean canInherit = jdbcTemplate.queryForObject(
                    "SELECT rolinherit FROM pg_roles WHERE rolname = 'stockguard_test_privileged'", Boolean.class);
            assertThat(canInherit).isFalse();

            String authSessionsOwner = jdbcTemplate.queryForObject(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'auth_sessions'", String.class);
            assertThat(authSessionsOwner).isNotEqualTo("stockguard_test_privileged");

            String refreshTokensOwner = jdbcTemplate.queryForObject(
                    "SELECT tableowner FROM pg_tables WHERE schemaname = 'public' AND tablename = 'refresh_tokens'", String.class);
            assertThat(refreshTokensOwner).isNotEqualTo("stockguard_test_privileged");

            // 2. Direct deletion rejected by trigger
            UUID userId = createTestUser("partc-c12");
            UUID sessionId = createTestSession(userId);
            UUID t1Id = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, ?)",
                    t1Id, sessionId, sampleHash("c12"), Timestamp.from(expiresAt)
            );

            // As non-owner privileged user (table DELETE granted), trigger fires and rejects direct deletion
            assertSqlException(
                    () -> privilegedJdbcTemplate.update("DELETE FROM refresh_tokens WHERE id = ?", t1Id),
                    "55000",
                    "Direct deletion of refresh_token"
            );
        }

        @Test
        @DisplayName("Case 13: Direct deletion of intermediate token is rejected by trigger while session exists (SQLSTATE 55000)")
        void directDeletionOfIntermediateTokenRejectedByTrigger() {
            UUID userId = createTestUser("partc-c13");
            UUID sessionId = createTestSession(userId);
            UUID t1Id = UUID.randomUUID();
            UUID t2Id = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 2, ?)",
                    t2Id, sessionId, sampleHash("c13-2"), Timestamp.from(expiresAt)
            );
            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, consumed_at, replaced_by_token_id, expires_at) " +
                    "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP, ?, ?)",
                    t1Id, sessionId, sampleHash("c13-1"), t2Id, Timestamp.from(expiresAt)
            );

            assertSqlException(
                    () -> privilegedJdbcTemplate.update("DELETE FROM refresh_tokens WHERE id = ?", t2Id),
                    "55000",
                    "Direct deletion of refresh_token"
            );
        }

        @Test
        @DisplayName("Case 14: Immutable column UPDATE is rejected by trigger including NULL transitions (SQLSTATE 55000)")
        void immutableColumnUpdateRejectedByTrigger() {
            UUID userId = createTestUser("partc-c14");
            UUID sessionId = createTestSession(userId);
            UUID t1Id = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, ?)",
                    t1Id, sessionId, sampleHash("c14"), Timestamp.from(expiresAt)
            );

            // 1. Update on immutable token_hash rejected by trigger
            assertSqlException(
                    () -> privilegedJdbcTemplate.update(
                            "UPDATE refresh_tokens SET token_hash = ? WHERE id = ?",
                            sampleHash("c14-tampered"), t1Id
                    ),
                    "55000",
                    "Updates to immutable columns of refresh_tokens"
            );

            // 2. Update on immutable session_id rejected by trigger
            assertSqlException(
                    () -> privilegedJdbcTemplate.update(
                            "UPDATE refresh_tokens SET session_id = ? WHERE id = ?",
                            UUID.randomUUID(), t1Id
                    ),
                    "55000",
                    "Updates to immutable columns of refresh_tokens"
            );

            // 3. NULL mutation on immutable column rejected by trigger (verifying IS DISTINCT FROM)
            assertSqlException(
                    () -> privilegedJdbcTemplate.update(
                            "UPDATE refresh_tokens SET token_hash = NULL WHERE id = ?",
                            t1Id
                    ),
                    "55000",
                    "Updates to immutable columns of refresh_tokens"
            );

            // 4. NULL mutation on sequence_number rejected by trigger
            assertSqlException(
                    () -> privilegedJdbcTemplate.update(
                            "UPDATE refresh_tokens SET sequence_number = NULL WHERE id = ?",
                            t1Id
                    ),
                    "55000",
                    "Updates to immutable columns of refresh_tokens"
            );
        }

        @Test
        @DisplayName("Case 15: Consumed token reactivation is rejected by trigger (SQLSTATE 55000)")
        void consumedTokenReactivationRejectedByTrigger() {
            UUID userId = createTestUser("partc-c15");
            UUID sessionId = createTestSession(userId);
            UUID t1Id = UUID.randomUUID();
            UUID t2Id = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 2, ?)",
                    t2Id, sessionId, sampleHash("c15-2"), Timestamp.from(expiresAt)
            );
            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, consumed_at, replaced_by_token_id, expires_at) " +
                    "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP, ?, ?)",
                    t1Id, sessionId, sampleHash("c15-1"), t2Id, Timestamp.from(expiresAt)
            );

            assertSqlException(
                    () -> privilegedJdbcTemplate.update(
                            "UPDATE refresh_tokens SET consumed_at = NULL, replaced_by_token_id = NULL WHERE id = ?",
                            t1Id
                    ),
                    "55000",
                    "Consumed refresh tokens are terminal and cannot be modified"
            );
        }

        @Test
        @DisplayName("Case 16: Replacement pointer modification is rejected by trigger (SQLSTATE 55000)")
        void replacementPointerModificationRejectedByTrigger() {
            UUID userId = createTestUser("partc-c16");
            UUID sessionId = createTestSession(userId);
            UUID t1Id = UUID.randomUUID();
            UUID t2Id = UUID.randomUUID();
            UUID t3Id = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 3, ?)",
                    t3Id, sessionId, sampleHash("c16-3"), Timestamp.from(expiresAt)
            );
            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, consumed_at, replaced_by_token_id, expires_at) " +
                    "VALUES (?, ?, ?, 2, CURRENT_TIMESTAMP, ?, ?)",
                    t2Id, sessionId, sampleHash("c16-2"), t3Id, Timestamp.from(expiresAt)
            );
            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, consumed_at, replaced_by_token_id, expires_at) " +
                    "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP, ?, ?)",
                    t1Id, sessionId, sampleHash("c16-1"), t2Id, Timestamp.from(expiresAt)
            );

            assertSqlException(
                    () -> privilegedJdbcTemplate.update(
                            "UPDATE refresh_tokens SET replaced_by_token_id = ? WHERE id = ?",
                            t3Id, t1Id
                    ),
                    "55000",
                    "Consumed refresh tokens are terminal and cannot be modified"
            );
        }

        @Test
        @DisplayName("Case 16b: Direct deletion of active child in multi-generation lineage is rejected by trigger while session exists (SQLSTATE 55000)")
        void directDeletionOfActiveChildInMultiGenerationLineageRejected() {
            // 1. Set up multi-generation lineage: T1 (consumed -> T2) -> T2 (consumed -> T3) -> T3 (active)
            UUID u1 = createTestUser("partc-multi1");
            UUID s1 = createTestSession(u1);
            UUID t1Id = UUID.randomUUID();
            UUID t2Id = UUID.randomUUID();
            UUID t3Id = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 3, ?)",
                    t3Id, s1, sampleHash("s1-t3"), Timestamp.from(expiresAt)
            );
            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, consumed_at, replaced_by_token_id, expires_at) " +
                    "VALUES (?, ?, ?, 2, CURRENT_TIMESTAMP, ?, ?)",
                    t2Id, s1, sampleHash("s1-t2"), t3Id, Timestamp.from(expiresAt)
            );
            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, consumed_at, replaced_by_token_id, expires_at) " +
                    "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP, ?, ?)",
                    t1Id, s1, sampleHash("s1-t1"), t2Id, Timestamp.from(expiresAt)
            );

            // Attempt direct deletion of active child token T3 in multi-generation lineage while session exists
            assertSqlException(
                    () -> privilegedJdbcTemplate.update("DELETE FROM refresh_tokens WHERE id = ?", t3Id),
                    "55000",
                    "Direct deletion of refresh_token"
            );

            // 2. Also assert cross-session replacement linkage is rejected by composite foreign key
            UUID s2 = createTestSession(u1);
            UUID tOtherId = UUID.randomUUID();
            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, ?)",
                    tOtherId, s2, sampleHash("s2-tok"), Timestamp.from(expiresAt)
            );

            assertSqlException(
                    () -> privilegedJdbcTemplate.update(
                            "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, consumed_at, replaced_by_token_id, expires_at) " +
                            "VALUES (?, ?, ?, 4, CURRENT_TIMESTAMP, ?, ?)",
                            UUID.randomUUID(), s1, sampleHash("s1-cross"), tOtherId, Timestamp.from(expiresAt)
                    ),
                    "23503",
                    "fk_refresh_tokens_same_session_replacement"
            );
        }
    }

    // ========================================================================
    // Part D: Cascading Complete Session Cleanup Tests (Role: stockguard_test_privileged) (2 Cases)
    // ========================================================================

    @Nested
    @DisplayName("Part D: Cascading Complete Session Cleanup Tests (Role: stockguard_test_privileged)")
    class CascadingSessionCleanupTests {

        @Test
        @DisplayName("Case 17: Cascade deletion of entire session family succeeds for non-superuser")
        void cascadeDeletionOfSessionFamilySucceeds() {
            UUID userId = createTestUser("partd-c17");
            UUID sessionId = createTestSession(userId);
            UUID t1Id = UUID.randomUUID();
            UUID t2Id = UUID.randomUUID();
            UUID t3Id = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 3, ?)",
                    t3Id, sessionId, sampleHash("d17-3"), Timestamp.from(expiresAt)
            );
            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, consumed_at, replaced_by_token_id, expires_at) " +
                    "VALUES (?, ?, ?, 2, CURRENT_TIMESTAMP, ?, ?)",
                    t2Id, sessionId, sampleHash("d17-2"), t3Id, Timestamp.from(expiresAt)
            );
            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, consumed_at, replaced_by_token_id, expires_at) " +
                    "VALUES (?, ?, ?, 1, CURRENT_TIMESTAMP, ?, ?)",
                    t1Id, sessionId, sampleHash("d17-1"), t2Id, Timestamp.from(expiresAt)
            );

            // Delete complete session family using non-owner, non-superuser role
            int deletedSessions = privilegedJdbcTemplate.update("DELETE FROM auth_sessions WHERE id = ?", sessionId);
            assertThat(deletedSessions).isEqualTo(1);

            Integer sessionCount = privilegedJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM auth_sessions WHERE id = ?", Integer.class, sessionId
            );
            assertThat(sessionCount).isEqualTo(0);
        }

        @Test
        @DisplayName("Case 18: Zero orphan tokens remain after session family deletion")
        void zeroOrphanTokensRemainAfterSessionDeletion() {
            UUID userId = createTestUser("partd-c18");
            UUID sessionId = createTestSession(userId);
            UUID t1Id = UUID.randomUUID();
            Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

            privilegedJdbcTemplate.update(
                    "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at) " +
                    "VALUES (?, ?, ?, 1, ?)",
                    t1Id, sessionId, sampleHash("d18-1"), Timestamp.from(expiresAt)
            );

            privilegedJdbcTemplate.update("DELETE FROM auth_sessions WHERE id = ?", sessionId);

            Integer orphanCount = privilegedJdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM refresh_tokens WHERE session_id = ?", Integer.class, sessionId
            );
            assertThat(orphanCount).isEqualTo(0);
        }
    }
}
