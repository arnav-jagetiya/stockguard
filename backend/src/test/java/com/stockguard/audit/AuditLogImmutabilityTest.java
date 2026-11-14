package com.stockguard.audit;

import com.stockguard.audit.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class AuditLogImmutabilityTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("stockguard_test")
            .withUsername("test_user")
            .withPassword("test_pass")
            .withInitScript("init-test-roles.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "stockguard_app");
        registry.add("spring.datasource.password", () -> "test_app_pass");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "stockguard_owner");
        registry.add("spring.flyway.password", () -> "test_owner_pass");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("stockguard.reservation.expiration.enabled", () -> "false");
    }

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID orgId;
    private UUID actorUserId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        String email = "immutability.actor-" + UUID.randomUUID() + "@example.com";

        jdbcTemplate.update("INSERT INTO organizations (id, name) VALUES (?, ?)", orgId, "Immutability Org");
        jdbcTemplate.update("INSERT INTO users (id, email, normalized_email, password_hash) VALUES (?, ?, ?, ?)",
                actorUserId, email, email, "hash");
        jdbcTemplate.update("INSERT INTO organization_memberships (organization_id, user_id, role) VALUES (?, ?, ?)",
                orgId, actorUserId, "ADMIN");
    }

    @Test
    @DisplayName("V2 trigger rejects UPDATE operations on audit_logs")
    void v2TriggerRejectsUpdate() {
        auditLogService.logInventoryAdjustment(
                orgId,
                actorUserId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                15L,
                "Valid adjustment",
                null
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE audit_logs SET action = 'MODIFIED_ACTION' WHERE organization_id = ?",
                orgId
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Table audit_logs is append-only");
    }

    @Test
    @DisplayName("V2 trigger rejects DELETE operations on audit_logs")
    void v2TriggerRejectsDelete() {
        auditLogService.logInventoryAdjustment(
                orgId,
                actorUserId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                15L,
                "Valid adjustment",
                null
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM audit_logs WHERE organization_id = ?",
                orgId
        ))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Table audit_logs is append-only");
    }

    @Test
    @DisplayName("V2 trigger rejects TRUNCATE operations on audit_logs and app role lacks TRUNCATE privilege")
    void truncateIsProhibited() throws Exception {
        // 1. Least-privilege app role has no TRUNCATE grant
        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE TABLE audit_logs"))
                .isInstanceOf(DataAccessException.class)
                .satisfies(ex -> {
                    Throwable rootCause = org.springframework.core.NestedExceptionUtils.getRootCause(ex);
                    assertThat(rootCause).isInstanceOf(org.postgresql.util.PSQLException.class);
                    assertThat(rootCause.getMessage()).contains("permission denied for table audit_logs");
                });

        // 2. Even table owner is blocked by the V2 append-only statement-level trigger
        try (var conn = java.sql.DriverManager.getConnection(
                postgres.getJdbcUrl(), "stockguard_owner", "test_owner_pass");
             var stmt = conn.createStatement()) {
            assertThatThrownBy(() -> stmt.execute("TRUNCATE TABLE audit_logs"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .hasMessageContaining("Table audit_logs is append-only: TRUNCATE operations are strictly prohibited");
        }
    }

    @Test
    @DisplayName("Ordinary INSERT and SELECT operations remain fully permitted under stockguard_app role")
    void insertAndSelectOperationsSucceed() {
        UUID prodId = UUID.randomUUID();
        auditLogService.logInventoryAdjustment(
                orgId,
                actorUserId,
                prodId,
                UUID.randomUUID(),
                50L,
                "Permitted adjustment",
                null
        );

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE organization_id = ? AND entity_id = ?",
                Integer.class,
                orgId,
                prodId
        );
        assertThat(count).isEqualTo(1);
    }
}
