package com.stockguard.infrastructure;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class DatabaseV4SchemaIntegrityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("stockguard_test")
            .withUsername("test_user")
            .withPassword("test_pass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("stockguard.security.bootstrap.enabled", () -> "false");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record TenantFixture(UUID orgId, UUID userId, UUID prodId, UUID whId) {}

    private TenantFixture createTenantFixture(String prefix) {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID prodId = UUID.randomUUID();
        UUID whId = UUID.randomUUID();

        String email = prefix.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String sku = (prefix + "-SKU-" + UUID.randomUUID().toString().substring(0, 6)).toUpperCase();
        String code = ("WH-" + UUID.randomUUID().toString().substring(0, 6)).toUpperCase();

        jdbcTemplate.update("INSERT INTO organizations (id, name) VALUES (?, ?)", orgId, "Org " + prefix);
        jdbcTemplate.update("INSERT INTO users (id, email, normalized_email, password_hash) VALUES (?, ?, ?, ?)",
                userId, email, email.toLowerCase(), "hash");
        jdbcTemplate.update("INSERT INTO organization_memberships (id, organization_id, user_id, role, active) VALUES (?, ?, ?, 'ADMIN', true)",
                UUID.randomUUID(), orgId, userId);
        jdbcTemplate.update("INSERT INTO products (id, organization_id, sku, normalized_sku, name) VALUES (?, ?, ?, ?, ?)",
                prodId, orgId, sku, sku, "Prod " + prefix);
        jdbcTemplate.update("INSERT INTO warehouses (id, organization_id, code, normalized_code, name, priority) VALUES (?, ?, ?, ?, ?, 1)",
                whId, orgId, code, code, "Warehouse " + prefix);

        return new TenantFixture(orgId, userId, prodId, whId);
    }

    private TenantFixture fixtureA;
    private TenantFixture fixtureB;

    @BeforeEach
    void setUp() {
        fixtureA = createTenantFixture("V4_A");
        fixtureB = createTenantFixture("V4_B");
    }

    @Test
    @DisplayName("V4 migration applies cleanly over V1-V3 and creates reconciliation_discrepancies")
    void v4MigrationAppliesCleanly() {
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'reconciliation_discrepancies'",
                Integer.class
        );
        assertThat(tableCount).isEqualTo(1);
    }

    @Nested
    @DisplayName("Composite Foreign Key Constraints")
    class CompositeForeignKeyTests {

        @Test
        @DisplayName("Product from another organization is rejected by composite foreign key")
        void productFromAnotherTenantIsRejected() {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id,
                    discrepancy_type, status
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'OPEN')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureB.prodId(), fixtureA.whId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_discrepancies_product");
        }

        @Test
        @DisplayName("Warehouse from another organization is rejected by composite foreign key")
        void warehouseFromAnotherTenantIsRejected() {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id,
                    discrepancy_type, status
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'OPEN')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), fixtureB.whId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_discrepancies_warehouse");
        }

        @Test
        @DisplayName("Assignee user from another organization is rejected by composite membership foreign key")
        void assigneeFromAnotherTenantIsRejected() {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id,
                    discrepancy_type, status, assigned_to_user_id
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'INVESTIGATING', ?)
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId(), fixtureB.userId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_discrepancies_assigned_membership");
        }

        @Test
        @DisplayName("Resolver user from another organization is rejected by composite membership foreign key")
        void resolverFromAnotherTenantIsRejected() {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id,
                    discrepancy_type, status, resolution_source, resolved_by_user_id,
                    resolved_at, resolution_reason
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'RESOLVED', 'HUMAN', ?, NOW(), 'Resolved by manager')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId(), fixtureB.userId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("fk_discrepancies_resolved_membership");
        }

        @Test
        @DisplayName("Clearing assignment sets assigned_to_user_id to null while keeping organization_id intact")
        void clearingAssignmentPreservesOrganizationId() {
            UUID discId = UUID.randomUUID();
            jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id,
                    discrepancy_type, status, assigned_to_user_id
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'INVESTIGATING', ?)
                """, discId, fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId(), fixtureA.userId());

            jdbcTemplate.update("""
                UPDATE reconciliation_discrepancies
                SET assigned_to_user_id = NULL, status = 'ACKNOWLEDGED'
                WHERE id = ?
                """, discId);

            UUID assignedUser = jdbcTemplate.queryForObject(
                    "SELECT assigned_to_user_id FROM reconciliation_discrepancies WHERE id = ?",
                    UUID.class, discId);
            UUID orgId = jdbcTemplate.queryForObject(
                    "SELECT organization_id FROM reconciliation_discrepancies WHERE id = ?",
                    UUID.class, discId);

            assertThat(assignedUser).isNull();
            assertThat(orgId).isEqualTo(fixtureA.orgId());
        }

        @Test
        @DisplayName("System resolution with null resolver satisfies foreign key via MATCH SIMPLE")
        void systemResolutionWithNullResolverSucceeds() {
            UUID discId = UUID.randomUUID();
            assertThatCode(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id,
                    discrepancy_type, status, resolution_source, resolved_by_user_id,
                    resolved_at, resolution_reason
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'RESOLVED', 'SYSTEM', NULL, NOW(), 'Auto-resolved by worker')
                """, discId, fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("ON DELETE RESTRICT prevents deleting organization membership referenced by discrepancy")
        void onDeleteRestrictProtectsReferencedMembership() {
            UUID discId = UUID.randomUUID();
            jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id,
                    discrepancy_type, status, assigned_to_user_id
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'INVESTIGATING', ?)
                """, discId, fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId(), fixtureA.userId());

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "DELETE FROM organization_memberships WHERE organization_id = ? AND user_id = ?",
                    fixtureA.orgId(), fixtureA.userId()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("Partial Unique Index Deduplication")
    class PartialUniqueIndexTests {

        @Test
        @DisplayName("Duplicate active discrepancy for same (org, product, warehouse) position is rejected")
        void duplicateActiveDiscrepancyIsRejected() {
            jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id,
                    discrepancy_type, status
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'OPEN')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId());

            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id,
                    discrepancy_type, status
                ) VALUES (?, ?, ?, ?, 'MISSING_BALANCE_ROW', 'INVESTIGATING')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uq_active_discrepancy_position");
        }

        @Test
        @DisplayName("Multiple terminal discrepancies for same position are allowed after resolution")
        void multipleTerminalDiscrepanciesAreAllowed() {
            UUID disc1 = UUID.randomUUID();
            UUID disc2 = UUID.randomUUID();
            UUID disc3 = UUID.randomUUID();

            // First resolved
            jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id,
                    discrepancy_type, status, resolution_source, resolved_at, resolution_reason
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'RESOLVED', 'SYSTEM', NOW(), 'Resolved 1')
                """, disc1, fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId());

            // Second resolved
            jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id,
                    discrepancy_type, status, resolution_source, resolved_by_user_id, resolved_at, resolution_reason
                ) VALUES (?, ?, ?, ?, 'MISSING_BALANCE_ROW', 'REJECTED', 'HUMAN', ?, NOW(), 'Rejected 2')
                """, disc2, fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId(), fixtureA.userId());

            // Third active (OPEN)
            assertThatCode(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id,
                    discrepancy_type, status
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'OPEN')
                """, disc3, fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId()))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("Null-Safe Resolution Consistency CHECK Constraint")
    class ResolutionConsistencyCheckTests {

        @Test
        @DisplayName("Valid states succeed: OPEN, ACKNOWLEDGED, INVESTIGATING, SYSTEM RESOLVED, HUMAN RESOLVED, HUMAN REJECTED")
        void validStatesSucceed() {
            // OPEN
            assertThatCode(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id, discrepancy_type, status
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'OPEN')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId()))
                    .doesNotThrowAnyException();

            // ACKNOWLEDGED (using different warehouse)
            UUID wh2 = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO warehouses (id, organization_id, code, normalized_code, name, priority) VALUES (?, ?, 'W2', 'W2', 'W2', 2)",
                    wh2, fixtureA.orgId());
            assertThatCode(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id, discrepancy_type, status
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'ACKNOWLEDGED')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), wh2))
                    .doesNotThrowAnyException();

            // INVESTIGATING
            UUID wh3 = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO warehouses (id, organization_id, code, normalized_code, name, priority) VALUES (?, ?, 'W3', 'W3', 'W3', 3)",
                    wh3, fixtureA.orgId());
            assertThatCode(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id, discrepancy_type, status, assigned_to_user_id
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'INVESTIGATING', ?)
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), wh3, fixtureA.userId()))
                    .doesNotThrowAnyException();

            // SYSTEM RESOLVED
            UUID wh4 = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO warehouses (id, organization_id, code, normalized_code, name, priority) VALUES (?, ?, 'W4', 'W4', 'W4', 4)",
                    wh4, fixtureA.orgId());
            assertThatCode(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id, discrepancy_type, status,
                    resolution_source, resolved_by_user_id, resolved_at, resolution_reason
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'RESOLVED', 'SYSTEM', NULL, NOW(), 'Auto resolved')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), wh4))
                    .doesNotThrowAnyException();

            // HUMAN RESOLVED
            UUID wh5 = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO warehouses (id, organization_id, code, normalized_code, name, priority) VALUES (?, ?, 'W5', 'W5', 'W5', 5)",
                    wh5, fixtureA.orgId());
            assertThatCode(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id, discrepancy_type, status,
                    resolution_source, resolved_by_user_id, resolved_at, resolution_reason
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'RESOLVED', 'HUMAN', ?, NOW(), 'Manual resolved')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), wh5, fixtureA.userId()))
                    .doesNotThrowAnyException();

            // HUMAN REJECTED
            UUID wh6 = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO warehouses (id, organization_id, code, normalized_code, name, priority) VALUES (?, ?, 'W6', 'W6', 'W6', 6)",
                    wh6, fixtureA.orgId());
            assertThatCode(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id, discrepancy_type, status,
                    resolution_source, resolved_by_user_id, resolved_at, resolution_reason
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'REJECTED', 'HUMAN', ?, NOW(), 'Manual rejected')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), wh6, fixtureA.userId()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("NULL resolution_source on RESOLVED fails check constraint")
        void nullResolutionSourceOnResolvedFails() {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id, discrepancy_type, status,
                    resolution_source, resolved_by_user_id, resolved_at, resolution_reason
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'RESOLVED', NULL, NULL, NOW(), 'Reason')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_discrepancies_resolution_consistency");
        }

        @Test
        @DisplayName("NULL resolution_source on REJECTED fails check constraint")
        void nullResolutionSourceOnRejectedFails() {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id, discrepancy_type, status,
                    resolution_source, resolved_by_user_id, resolved_at, resolution_reason
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'REJECTED', NULL, ?, NOW(), 'Reason')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId(), fixtureA.userId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_discrepancies_resolution_consistency");
        }

        @Test
        @DisplayName("SYSTEM resolution with non-null resolver fails check constraint")
        void systemResolutionWithResolverFails() {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id, discrepancy_type, status,
                    resolution_source, resolved_by_user_id, resolved_at, resolution_reason
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'RESOLVED', 'SYSTEM', ?, NOW(), 'Reason')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId(), fixtureA.userId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_discrepancies_resolution_consistency");
        }

        @Test
        @DisplayName("HUMAN resolution with null resolver fails check constraint")
        void humanResolutionWithNullResolverFails() {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id, discrepancy_type, status,
                    resolution_source, resolved_by_user_id, resolved_at, resolution_reason
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'RESOLVED', 'HUMAN', NULL, NOW(), 'Reason')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_discrepancies_resolution_consistency");
        }

        @Test
        @DisplayName("Missing resolved_at timestamp fails check constraint")
        void missingTimestampFails() {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id, discrepancy_type, status,
                    resolution_source, resolved_by_user_id, resolved_at, resolution_reason
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'RESOLVED', 'SYSTEM', NULL, NULL, 'Reason')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_discrepancies_resolution_consistency");
        }

        @Test
        @DisplayName("Whitespace-only reasons (spaces, tabs, newlines) fail regex nonblank check")
        void whitespaceOnlyReasonsFail() {
            // Spaces only
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id, discrepancy_type, status,
                    resolution_source, resolved_by_user_id, resolved_at, resolution_reason
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'RESOLVED', 'SYSTEM', NULL, NOW(), '   ')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_discrepancies_resolution_consistency");

            // Tabs only
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id, discrepancy_type, status,
                    resolution_source, resolved_by_user_id, resolved_at, resolution_reason
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'RESOLVED', 'SYSTEM', NULL, NOW(), E'\t\t')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_discrepancies_resolution_consistency");

            // Newlines only
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id, discrepancy_type, status,
                    resolution_source, resolved_by_user_id, resolved_at, resolution_reason
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'RESOLVED', 'SYSTEM', NULL, NOW(), E'\n\r\n')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_discrepancies_resolution_consistency");
        }

        @Test
        @DisplayName("System rejection is prohibited by check constraint")
        void systemRejectionIsProhibited() {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id, discrepancy_type, status,
                    resolution_source, resolved_by_user_id, resolved_at, resolution_reason
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'REJECTED', 'SYSTEM', NULL, NOW(), 'Auto reject')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_discrepancies_resolution_consistency");
        }

        @Test
        @DisplayName("Active state with resolution metadata fails check constraint")
        void activeStateWithResolutionMetadataFails() {
            assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO reconciliation_discrepancies (
                    id, organization_id, product_id, warehouse_id, discrepancy_type, status,
                    resolution_source
                ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'OPEN', 'SYSTEM')
                """, UUID.randomUUID(), fixtureA.orgId(), fixtureA.prodId(), fixtureA.whId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("chk_discrepancies_resolution_consistency");
        }
    }
}
