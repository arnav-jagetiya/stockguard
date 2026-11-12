package com.stockguard.infrastructure;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.Socket;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Validates that Flyway executes V1, V2, and V3 migrations against PostgreSQL 16,
 * that Hibernate validates the resulting schema with ddl-auto: validate,
 * and that append-only immutability triggers strictly reject UPDATE, DELETE, and TRUNCATE.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
@org.springframework.test.context.TestPropertySource(properties = {
        "spring.datasource.username=stockguard_owner",
        "spring.datasource.password=test_owner_pass"
})
class FlywayMigrationAndSchemaValidationTest {

    private static final Set<String> EXPECTED_MVP_TABLES = Set.of(
            "organizations",
            "users",
            "organization_memberships",
            "products",
            "warehouses",
            "inventory_balances",
            "inventory_transactions",
            "inventory_ledger_entries",
            "orders",
            "order_items",
            "reservations",
            "reservation_allocations",
            "transfers",
            "transfer_items",
            "idempotency_records",
            "audit_logs",
            "auth_sessions",
            "refresh_tokens"
    );

    private static final Set<String> DEFERRED_TABLES = Set.of(
            "membership_roles",
            "system_settings",
            "api_clients"
    );

    @BeforeAll
    static void ensurePostgresIsAvailable() {
        int port = Integer.parseInt(System.getProperty("DB_PORT", System.getenv().getOrDefault("DB_PORT", "5433")));
        boolean postgresAvailable = false;
        try (Socket socket = new Socket("localhost", port)) {
            postgresAvailable = true;
        } catch (Exception ignored) {
        }
        Assumptions.assumeTrue(postgresAvailable,
                "PostgreSQL 16 is not accessible on localhost:" + port + "; skipping live migration test.");
    }

    @Autowired(required = false)
    private Flyway flyway;

    @Autowired(required = false)
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Flyway reports successful V1, V2, and V3 migration execution")
    void flywayReportsSuccessfulMigrations() {
        assertThat(flyway).isNotNull();
        MigrationInfo current = flyway.info().current();
        assertThat(current).isNotNull();
        assertThat(current.getVersion().getVersion()).isEqualTo("3");
        assertThat(current.getDescription()).isEqualTo("create auth and session tables");
        assertThat(current.getState().isApplied()).isTrue();

        MigrationInfo[] applied = flyway.info().applied();
        assertThat(applied).hasSize(3);
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied[0].getDescription()).isEqualTo("create initial schema");
        assertThat(applied[0].getState().isApplied()).isTrue();
        assertThat(applied[1].getVersion().getVersion()).isEqualTo("2");
        assertThat(applied[1].getDescription()).isEqualTo("enforce append only ledgers");
        assertThat(applied[1].getState().isApplied()).isTrue();
        assertThat(applied[2].getVersion().getVersion()).isEqualTo("3");
        assertThat(applied[2].getDescription()).isEqualTo("create auth and session tables");
        assertThat(applied[2].getState().isApplied()).isTrue();
    }

    @Test
    @DisplayName("All 18 tables exist (16 MVP tables + auth_sessions + refresh_tokens) in public schema and no deferred tables are created")
    void allSixteenMvpTablesExist() {
        assertThat(jdbcTemplate).isNotNull();

        List<String> actualTables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE'",
                String.class
        );

        // Verify all 16 MVP tables exist
        assertThat(actualTables).containsAll(EXPECTED_MVP_TABLES);

        // Verify no deferred tables exist
        for (String deferredTable : DEFERRED_TABLES) {
            assertThat(actualTables).doesNotContain(deferredTable);
        }

        // Only 16 MVP tables + flyway_schema_history should be present in public schema
        Set<String> expectedPlusFlyway = new java.util.HashSet<>(EXPECTED_MVP_TABLES);
        expectedPlusFlyway.add("flyway_schema_history");
        assertThat(actualTables).containsExactlyInAnyOrderElementsOf(expectedPlusFlyway);
    }

    @Test
    @DisplayName("Key constraints, check constraints, and operational indexes exist")
    void keyConstraintsAndIndexesExist() {
        assertThat(jdbcTemplate).isNotNull();

        // 1. Verify partial unique indexes (including V3 active refresh token uniqueness)
        List<String> partialIndexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' AND indexname IN ('idx_ledger_transfer_out', 'idx_ledger_transfer_in', 'idx_reservations_active_expires', 'uq_refresh_tokens_one_active_per_session')",
                String.class
        );
        assertThat(partialIndexes).containsExactlyInAnyOrder(
                "idx_ledger_transfer_out",
                "idx_ledger_transfer_in",
                "idx_reservations_active_expires",
                "uq_refresh_tokens_one_active_per_session"
        );

        // 2. Verify inventory balance check constraints
        List<String> balanceChecks = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = 'public' AND table_name = 'inventory_balances' AND constraint_type = 'CHECK'",
                String.class
        );
        assertThat(balanceChecks).contains(
                "chk_inventory_balances_on_hand_non_negative",
                "chk_inventory_balances_reserved_non_negative",
                "chk_inventory_balances_reserved_le_on_hand"
        );

        // 3. Verify cross-table ledger foreign key to transfer_items
        List<String> ledgerFks = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = 'public' AND table_name = 'inventory_ledger_entries' AND constraint_type = 'FOREIGN KEY'",
                String.class
        );
        assertThat(ledgerFks).contains("fk_inventory_ledger_entries_transfer_item");

        // 4. Verify idempotency unique constraint on scope
        List<String> idempotencyConstraints = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints WHERE table_schema = 'public' AND table_name = 'idempotency_records' AND constraint_type = 'UNIQUE'",
                String.class
        );
        assertThat(idempotencyConstraints).contains("uq_idempotency_records_scope");
    }

    @Test
    @DisplayName("inventory_ledger_entries rejects UPDATE, DELETE, and TRUNCATE statements")
    void inventoryLedgerEntriesRejectsMutations() {
        assertThat(jdbcTemplate).isNotNull();

        // 1. UPDATE rejected (even with 0 matching rows)
        assertThatThrownBy(() ->
                jdbcTemplate.execute("UPDATE inventory_ledger_entries SET on_hand_delta = 10 WHERE 1 = 0")
        ).hasMessageContaining("append-only")
         .hasMessageContaining("UPDATE");

        // 2. DELETE rejected (even with 0 matching rows)
        assertThatThrownBy(() ->
                jdbcTemplate.execute("DELETE FROM inventory_ledger_entries WHERE 1 = 0")
        ).hasMessageContaining("append-only")
         .hasMessageContaining("DELETE");

        // 3. TRUNCATE rejected
        assertThatThrownBy(() ->
                jdbcTemplate.execute("TRUNCATE TABLE inventory_ledger_entries")
        ).hasMessageContaining("append-only")
         .hasMessageContaining("TRUNCATE");
    }

    @Test
    @DisplayName("audit_logs rejects UPDATE, DELETE, and TRUNCATE statements")
    void auditLogsRejectsMutations() {
        assertThat(jdbcTemplate).isNotNull();

        // 1. UPDATE rejected (even with 0 matching rows)
        assertThatThrownBy(() ->
                jdbcTemplate.execute("UPDATE audit_logs SET action = 'MODIFIED' WHERE 1 = 0")
        ).hasMessageContaining("append-only")
         .hasMessageContaining("UPDATE");

        // 2. DELETE rejected (even with 0 matching rows)
        assertThatThrownBy(() ->
                jdbcTemplate.execute("DELETE FROM audit_logs WHERE 1 = 0")
        ).hasMessageContaining("append-only")
         .hasMessageContaining("DELETE");

        // 3. TRUNCATE rejected
        assertThatThrownBy(() ->
                jdbcTemplate.execute("TRUNCATE TABLE audit_logs")
        ).hasMessageContaining("append-only")
         .hasMessageContaining("TRUNCATE");
    }

    @Test
    @DisplayName("Ordinary INSERT operations remain permitted on audit_logs and inventory_ledger_entries")
    void ordinaryInsertsRemainPermitted() {
        assertThat(jdbcTemplate).isNotNull();

        TransactionTemplate tx = new TransactionTemplate(
                new DataSourceTransactionManager(jdbcTemplate.getDataSource())
        );

        tx.execute(status -> {
            try {
                UUID orgId = UUID.randomUUID();
                UUID userId = UUID.randomUUID();
                UUID prodId = UUID.randomUUID();
                UUID whId = UUID.randomUUID();
                UUID txId = UUID.randomUUID();
                UUID ledgerId = UUID.randomUUID();
                UUID auditId = UUID.randomUUID();

                // Setup parent entities
                jdbcTemplate.update("INSERT INTO organizations (id, name) VALUES (?, ?)", orgId, "Test Org");
                jdbcTemplate.update("INSERT INTO users (id, email, normalized_email) VALUES (?, ?, ?)",
                        userId, "test@example.com", "test@example.com");
                jdbcTemplate.update("INSERT INTO organization_memberships (organization_id, user_id, role) VALUES (?, ?, ?)",
                        orgId, userId, "ADMIN");
                jdbcTemplate.update("INSERT INTO products (id, organization_id, sku, normalized_sku, name) VALUES (?, ?, ?, ?, ?)",
                        prodId, orgId, "SKU-001", "SKU-001", "Test Product");
                jdbcTemplate.update("INSERT INTO warehouses (id, organization_id, code, normalized_code, name, priority) VALUES (?, ?, ?, ?, ?, ?)",
                        whId, orgId, "WH-01", "WH-01", "Main Warehouse", 1);
                jdbcTemplate.update("INSERT INTO inventory_transactions (id, organization_id, transaction_type) VALUES (?, ?, ?)",
                        txId, orgId, "RECEIPT");

                // Test INSERT into audit_logs
                int auditRows = jdbcTemplate.update(
                        "INSERT INTO audit_logs (id, organization_id, action, entity_type) VALUES (?, ?, ?, ?)",
                        auditId, orgId, "TEST_ACTION", "organization"
                );
                assertThat(auditRows).isEqualTo(1);

                // Test INSERT into inventory_ledger_entries
                int ledgerRows = jdbcTemplate.update(
                        "INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        ledgerId, orgId, txId, 1, prodId, whId, 10L, 0L
                );
                assertThat(ledgerRows).isEqualTo(1);

                // Verify rows exist before rollback
                Integer auditCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM audit_logs WHERE id = ?", Integer.class, auditId);
                assertThat(auditCount).isEqualTo(1);

                Integer ledgerCount = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM inventory_ledger_entries WHERE id = ?", Integer.class, ledgerId);
                assertThat(ledgerCount).isEqualTo(1);
            } finally {
                // Always roll back to keep database clean
                status.setRollbackOnly();
            }
            return null;
        });
    }
}
