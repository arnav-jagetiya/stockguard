package com.stockguard.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockguard.audit.domain.AuditAction;
import com.stockguard.audit.domain.AuditEntityType;
import com.stockguard.audit.domain.AuditLog;
import com.stockguard.audit.service.AuditLogService;
import com.stockguard.audit.util.SensitiveDataMasker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class AuditLogCoreIntegrationTest {

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

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID orgId;
    private UUID actorUserId;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        actorUserId = UUID.randomUUID();
        String email = "audit.actor-" + UUID.randomUUID() + "@example.com";

        jdbcTemplate.update("INSERT INTO organizations (id, name) VALUES (?, ?)", orgId, "Audit Test Org");
        jdbcTemplate.update("INSERT INTO users (id, email, normalized_email, password_hash) VALUES (?, ?, ?, ?)",
                actorUserId, email, email, "hash");
        jdbcTemplate.update("INSERT INTO organization_memberships (organization_id, user_id, role) VALUES (?, ?, ?)",
                orgId, actorUserId, "ADMIN");
    }

    @Test
    @DisplayName("Successful business audit insertion and retrieval via purpose-specific methods")
    void successfulAuditInsertionAndRetrieval() throws Exception {
        UUID productId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();

        auditLogService.logInventoryAdjustment(
                orgId,
                actorUserId,
                productId,
                warehouseId,
                25L,
                "Cycle count adjustment",
                txId
        );

        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgId,
                AuditEntityType.INVENTORY_BALANCE,
                AuditAction.STOCK_ADJUSTED,
                actorUserId,
                null,
                null,
                10,
                0
        );

        assertThat(logs).hasSize(1);
        AuditLog log = logs.getFirst();
        assertThat(log.getOrganizationId()).isEqualTo(orgId);
        assertThat(log.getActorUserId()).isEqualTo(actorUserId);
        assertThat(log.getAction()).isEqualTo(AuditAction.STOCK_ADJUSTED);
        assertThat(log.getEntityType()).isEqualTo(AuditEntityType.INVENTORY_BALANCE);
        assertThat(log.getEntityId()).isEqualTo(productId);
        assertThat(log.getCreatedAt()).isNotNull();

        // Inspect metadata
        Map<String, Object> metadata = objectMapper.readValue(log.getMetadata(), new TypeReference<>() {});
        assertThat(metadata)
                .containsEntry("productId", productId.toString())
                .containsEntry("warehouseId", warehouseId.toString())
                .containsEntry("onHandDelta", 25)
                .containsEntry("reason", "Cycle count adjustment")
                .containsEntry("transactionId", txId.toString());

        // Single ID retrieval
        Optional<AuditLog> byId = auditLogService.findByIdAndOrganizationId(log.getId(), orgId);
        assertThat(byId).isPresent();
        assertThat(byId.get().getId()).isEqualTo(log.getId());
    }

    @Test
    @DisplayName("Foreign key constraint rejects insertion with non-existent organization")
    void foreignKeyRejectsNonExistentOrganization() {
        UUID nonExistentOrgId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();

        assertThatThrownBy(() -> auditLogService.logInventoryAdjustment(
                nonExistentOrgId,
                actorUserId,
                productId,
                UUID.randomUUID(),
                10L,
                "Test",
                null
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("Foreign key constraint rejects actor who is not an active organization member")
    void foreignKeyRejectsNonMemberActor() {
        UUID nonMemberUserId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, normalized_email, password_hash) VALUES (?, ?, ?, ?)",
                nonMemberUserId, "other.user@example.com", "other.user@example.com", "hash");

        assertThatThrownBy(() -> auditLogService.logInventoryAdjustment(
                orgId,
                nonMemberUserId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                5L,
                "Unauthorized actor",
                null
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("System-generated events succeed with null actor_user_id")
    void systemActorWithNullActorUserIdSucceeds() {
        UUID reservationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        auditLogService.logReservationExpired(orgId, reservationId, orderId);

        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgId,
                AuditEntityType.RESERVATION,
                AuditAction.RESERVATION_EXPIRED,
                null,
                null,
                null,
                10,
                0
        );

        assertThat(logs).hasSize(1);
        AuditLog log = logs.getFirst();
        assertThat(log.getActorUserId()).isNull();
        assertThat(log.getAction()).isEqualTo(AuditAction.RESERVATION_EXPIRED);
    }

    @Test
    @DisplayName("SensitiveDataMasker replaces blacklisted credentials and keys as defense-in-depth")
    void sensitiveDataMaskerReplacesBlacklistedKeys() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("reason", "Standard cycle count");
        input.put("password", "plaintext_secret");
        input.put("rawPassword", "another_secret");
        input.put("jwt", "eyJhbGciOi...");
        input.put("access_token", "ya29.xyz");
        input.put("refreshToken", "opaque_refresh");
        input.put("apiKey", "api_key_123");
        input.put("clientSecret", "client_secret_xyz");
        input.put("nested", Map.of("tokenHash", "abc123hash", "safeKey", "safeValue"));

        Map<String, Object> masked = SensitiveDataMasker.mask(input);

        assertThat(masked.get("reason")).isEqualTo("Standard cycle count");
        assertThat(masked.get("password")).isEqualTo(SensitiveDataMasker.MASKED_VALUE);
        assertThat(masked.get("rawPassword")).isEqualTo(SensitiveDataMasker.MASKED_VALUE);
        assertThat(masked.get("jwt")).isEqualTo(SensitiveDataMasker.MASKED_VALUE);
        assertThat(masked.get("access_token")).isEqualTo(SensitiveDataMasker.MASKED_VALUE);
        assertThat(masked.get("refreshToken")).isEqualTo(SensitiveDataMasker.MASKED_VALUE);
        assertThat(masked.get("apiKey")).isEqualTo(SensitiveDataMasker.MASKED_VALUE);
        assertThat(masked.get("clientSecret")).isEqualTo(SensitiveDataMasker.MASKED_VALUE);

        @SuppressWarnings("unchecked")
        Map<String, Object> nested = (Map<String, Object>) masked.get("nested");
        assertThat(nested.get("tokenHash")).isEqualTo(SensitiveDataMasker.MASKED_VALUE);
        assertThat(nested.get("safeKey")).isEqualTo("safeValue");
    }

    @Test
    @DisplayName("Business audit record rolls back atomically when enclosing transaction rolls back")
    void businessAuditRollsBackWithEnclosingTransaction() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        tx.executeWithoutResult(status -> {
            auditLogService.logInventoryAdjustment(
                    orgId,
                    actorUserId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    100L,
                    "Pending adjustment",
                    null
            );
            // Simulate business transaction rollback
            status.setRollbackOnly();
        });

        long count = auditLogService.countAuditLogs(orgId, null, null, null, null, null);
        assertThat(count).isEqualTo(0L);
    }

    @Test
    @DisplayName("Independent security audit (REQUIRES_NEW) commits even if outer transaction rolls back")
    void independentSecurityAuditCommitsWhenOuterTransactionRollsBack() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        UUID sessionId = UUID.randomUUID();

        tx.executeWithoutResult(status -> {
            // Independent REQUIRES_NEW call
            auditLogService.logSessionRevoked(
                    orgId,
                    actorUserId,
                    sessionId,
                    "Replay attack detected"
            );
            // Roll back the outer transaction
            status.setRollbackOnly();
        });

        // The REQUIRES_NEW audit entry must have committed independently
        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgId,
                AuditEntityType.AUTH_SESSION,
                AuditAction.SESSION_REVOKED,
                actorUserId,
                null,
                null,
                10,
                0
        );

        assertThat(logs).hasSize(1);
        AuditLog log = logs.getFirst();
        assertThat(log.getAction()).isEqualTo(AuditAction.SESSION_REVOKED);
        assertThat(log.getEntityId()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("Reconciliation discrepancy metadata is strictly capped at 100 items with truncated flag")
    void reconciliationDiscrepancyMetadataCappedAt100Items() throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            Map<String, Object> item = new HashMap<>();
            item.put("productId", UUID.randomUUID().toString());
            item.put("warehouseId", UUID.randomUUID().toString());
            item.put("balanceOnHand", i);
            item.put("ledgerOnHand", i + 1);
            item.put("discrepancyStatus", "DELTA_MISMATCH");
            items.add(item);
        }

        auditLogService.logReconciliationDiscrepancy(
                orgId,
                500,
                350,
                150,
                items
        );

        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgId,
                AuditEntityType.RECONCILIATION,
                AuditAction.RECONCILIATION_DISCREPANCY_DETECTED,
                null,
                null,
                null,
                10,
                0
        );

        assertThat(logs).hasSize(1);
        AuditLog log = logs.getFirst();

        Map<String, Object> metadata = objectMapper.readValue(log.getMetadata(), new TypeReference<>() {});
        assertThat(metadata.get("totalDiscrepancyCount")).isEqualTo(150);
        assertThat(metadata.get("truncated")).isEqualTo(true);
        assertThat(metadata.get("maxRecordedItems")).isEqualTo(100);

        @SuppressWarnings("unchecked")
        List<Object> recordedItems = (List<Object>) metadata.get("items");
        assertThat(recordedItems).hasSize(100);
    }

    @Test
    @DisplayName("Multi-tenant isolation ensures queries strictly partition by organization_id")
    void multiTenantIsolationOnReads() {
        UUID orgB = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO organizations (id, name) VALUES (?, ?)", orgB, "Org B");
        jdbcTemplate.update("INSERT INTO users (id, email, normalized_email, password_hash) VALUES (?, ?, ?, ?)",
                userB, "user.b@example.com", "user.b@example.com", "hash");
        jdbcTemplate.update("INSERT INTO organization_memberships (organization_id, user_id, role) VALUES (?, ?, ?)",
                orgB, userB, "ADMIN");

        auditLogService.logOrderCreated(orgId, actorUserId, UUID.randomUUID(), "ORD-A1", BigDecimal.valueOf(100), 2);
        auditLogService.logOrderCreated(orgB, userB, UUID.randomUUID(), "ORD-B1", BigDecimal.valueOf(200), 4);

        List<AuditLog> orgALogs = auditLogService.findAuditLogs(orgId, null, null, null, null, null, 50, 0);
        List<AuditLog> orgBLogs = auditLogService.findAuditLogs(orgB, null, null, null, null, null, 50, 0);

        assertThat(orgALogs).hasSize(1);
        assertThat(orgBLogs).hasSize(1);
        assertThat(orgALogs.getFirst().getOrganizationId()).isEqualTo(orgId);
        assertThat(orgBLogs.getFirst().getOrganizationId()).isEqualTo(orgB);
    }
}
