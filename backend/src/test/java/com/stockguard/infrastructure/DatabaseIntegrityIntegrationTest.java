package com.stockguard.infrastructure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end database integrity integration test verifying that PostgreSQL 16
 * constraints and triggers enforce STOCKGUARD's approved domain invariants.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class DatabaseIntegrityIntegrationTest {

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
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ------------------------------------------------------------------------
    // Test Data Helpers
    // ------------------------------------------------------------------------

    private record TenantFixture(UUID orgId, UUID userId, UUID prodId, UUID whId) {}

    private TenantFixture createTenantFixture(String prefix) {
        UUID orgId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID prodId = UUID.randomUUID();
        UUID whId = UUID.randomUUID();

        String email = prefix.toLowerCase() + "_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
        String sku = (prefix + "-SKU-" + UUID.randomUUID().toString().substring(0, 6)).toUpperCase();
        String code = ("WH-" + UUID.randomUUID().toString().substring(0, 6)).toUpperCase();

        jdbcTemplate.update("INSERT INTO organizations (id, name) VALUES (?, ?)", orgId, prefix + "-Org");
        jdbcTemplate.update("INSERT INTO users (id, email, normalized_email) VALUES (?, ?, ?)",
                userId, email, email);
        jdbcTemplate.update("INSERT INTO organization_memberships (organization_id, user_id, role) VALUES (?, ?, ?)",
                orgId, userId, "ADMIN");
        jdbcTemplate.update("INSERT INTO products (id, organization_id, sku, normalized_sku, name) VALUES (?, ?, ?, ?, ?)",
                prodId, orgId, sku, sku, prefix + "-Product");
        jdbcTemplate.update("INSERT INTO warehouses (id, organization_id, code, normalized_code, name, priority) VALUES (?, ?, ?, ?, ?, ?)",
                whId, orgId, code, code, prefix + "-Warehouse", 1);

        return new TenantFixture(orgId, userId, prodId, whId);
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
    // 1. Inventory Balance Integrity
    // ========================================================================
    @Nested
    @DisplayName("1. Inventory Balance Integrity Tests")
    class InventoryBalanceIntegrityTests {

        @Test
        @DisplayName("Rejects negative on_hand balance")
        void rejectsNegativeOnHand() {
            TenantFixture fixture = createTenantFixture("BAL1");
            UUID balanceId = UUID.randomUUID();

            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_balances (id, organization_id, product_id, warehouse_id, on_hand, reserved) VALUES (?, ?, ?, ?, ?, ?)",
                            balanceId, fixture.orgId(), fixture.prodId(), fixture.whId(), -1L, 0L),
                    "23514",
                    "chk_inventory_balances_on_hand_non_negative"
            );
        }

        @Test
        @DisplayName("Rejects negative reserved balance")
        void rejectsNegativeReserved() {
            TenantFixture fixture = createTenantFixture("BAL2");
            UUID balanceId = UUID.randomUUID();

            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_balances (id, organization_id, product_id, warehouse_id, on_hand, reserved) VALUES (?, ?, ?, ?, ?, ?)",
                            balanceId, fixture.orgId(), fixture.prodId(), fixture.whId(), 10L, -1L),
                    "23514",
                    "chk_inventory_balances_reserved_non_negative"
            );
        }

        @Test
        @DisplayName("Rejects reserved greater than on_hand on insert and update")
        void rejectsReservedGreaterThanOnHand() {
            TenantFixture fixture = createTenantFixture("BAL3");
            UUID balanceId = UUID.randomUUID();

            // Insert: on_hand = 5, reserved = 10 -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_balances (id, organization_id, product_id, warehouse_id, on_hand, reserved) VALUES (?, ?, ?, ?, ?, ?)",
                            balanceId, fixture.orgId(), fixture.prodId(), fixture.whId(), 5L, 10L),
                    "23514",
                    "chk_inventory_balances_reserved_le_on_hand"
            );

            // Insert valid balance: on_hand = 20, reserved = 5
            jdbcTemplate.update(
                    "INSERT INTO inventory_balances (id, organization_id, product_id, warehouse_id, on_hand, reserved) VALUES (?, ?, ?, ?, ?, ?)",
                    balanceId, fixture.orgId(), fixture.prodId(), fixture.whId(), 20L, 5L);

            // Update: setting reserved = 25 (> on_hand 20) -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "UPDATE inventory_balances SET reserved = 25 WHERE id = ?",
                            balanceId),
                    "23514",
                    "chk_inventory_balances_reserved_le_on_hand"
            );
        }

        @Test
        @DisplayName("Accepts valid inventory balances and verifies derived available stock")
        void acceptsValidBalances() {
            TenantFixture fixture = createTenantFixture("BAL4");
            UUID balanceId = UUID.randomUUID();

            int inserted = jdbcTemplate.update(
                    "INSERT INTO inventory_balances (id, organization_id, product_id, warehouse_id, on_hand, reserved) VALUES (?, ?, ?, ?, ?, ?)",
                    balanceId, fixture.orgId(), fixture.prodId(), fixture.whId(), 100L, 30L);
            assertThat(inserted).isEqualTo(1);

            Long onHand = jdbcTemplate.queryForObject("SELECT on_hand FROM inventory_balances WHERE id = ?", Long.class, balanceId);
            Long reserved = jdbcTemplate.queryForObject("SELECT reserved FROM inventory_balances WHERE id = ?", Long.class, balanceId);
            assertThat(onHand).isEqualTo(100L);
            assertThat(reserved).isEqualTo(30L);
            assertThat(onHand - reserved).isEqualTo(70L); // Derived available inventory
        }
    }

    // ========================================================================
    // 2. Organization Isolation
    // ========================================================================
    @Nested
    @DisplayName("2. Organization Isolation Tests (Composite Foreign Keys)")
    class OrganizationIsolationTests {

        @Test
        @DisplayName("Rejects associating product from Organization A with Organization B balance")
        void rejectsCrossTenantProductInBalance() {
            TenantFixture tenantA = createTenantFixture("TENA");
            TenantFixture tenantB = createTenantFixture("TENB");
            UUID balanceId = UUID.randomUUID();

            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_balances (id, organization_id, product_id, warehouse_id, on_hand, reserved) VALUES (?, ?, ?, ?, ?, ?)",
                            balanceId, tenantB.orgId(), tenantA.prodId(), tenantB.whId(), 10L, 0L),
                    "23503",
                    "fk_inventory_balances_product"
            );
        }

        @Test
        @DisplayName("Rejects associating warehouse from Organization A with Organization B balance")
        void rejectsCrossTenantWarehouseInBalance() {
            TenantFixture tenantA = createTenantFixture("TENWHA");
            TenantFixture tenantB = createTenantFixture("TENWHB");
            UUID balanceId = UUID.randomUUID();

            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_balances (id, organization_id, product_id, warehouse_id, on_hand, reserved) VALUES (?, ?, ?, ?, ?, ?)",
                            balanceId, tenantB.orgId(), tenantB.prodId(), tenantA.whId(), 10L, 0L),
                    "23503",
                    "fk_inventory_balances_warehouse"
            );
        }

        @Test
        @DisplayName("Rejects order item referencing product from another organization")
        void rejectsCrossTenantProductInOrderItem() {
            TenantFixture tenantA = createTenantFixture("ORDPRODA");
            TenantFixture tenantB = createTenantFixture("ORDPRODB");
            UUID orderId = UUID.randomUUID();
            UUID itemId = UUID.randomUUID();

            jdbcTemplate.update(
                    "INSERT INTO orders (id, organization_id, order_number, status) VALUES (?, ?, ?, ?)",
                    orderId, tenantB.orgId(), "ORD-" + UUID.randomUUID().toString().substring(0, 8), "PENDING");

            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO order_items (id, organization_id, order_id, line_number, product_id, sku_snapshot, name_snapshot, quantity, unit_price) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            itemId, tenantB.orgId(), orderId, 1, tenantA.prodId(), "SKU-A", "Product A", 1L, new BigDecimal("10.0000")),
                    "23503",
                    "fk_order_items_product"
            );
        }

        @Test
        @DisplayName("Rejects reservation allocation referencing mismatched order, reservation, or product")
        void rejectsMismatchedReservationAllocation() {
            TenantFixture tenant = createTenantFixture("ALLOC");
            UUID order1Id = UUID.randomUUID();
            UUID order2Id = UUID.randomUUID();
            UUID res1Id = UUID.randomUUID();
            UUID item1Id = UUID.randomUUID();
            UUID prod2Id = UUID.randomUUID();

            // Product 2 in same organization
            String prod2Sku = ("PROD-2-" + UUID.randomUUID().toString().substring(0, 6)).toUpperCase();
            jdbcTemplate.update("INSERT INTO products (id, organization_id, sku, normalized_sku, name) VALUES (?, ?, ?, ?, ?)",
                    prod2Id, tenant.orgId(), prod2Sku, prod2Sku, "Product Two");

            // Order 1 with Item 1 (ordering prodId)
            jdbcTemplate.update("INSERT INTO orders (id, organization_id, order_number, status) VALUES (?, ?, ?, ?)",
                    order1Id, tenant.orgId(), "ORD-1-" + UUID.randomUUID().toString().substring(0, 8), "PENDING");
            jdbcTemplate.update("INSERT INTO order_items (id, organization_id, order_id, line_number, product_id, sku_snapshot, name_snapshot, quantity, unit_price) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    item1Id, tenant.orgId(), order1Id, 1, tenant.prodId(), "SKU-1", "Product One", 5L, new BigDecimal("10.0000"));

            // Order 2
            jdbcTemplate.update("INSERT INTO orders (id, organization_id, order_number, status) VALUES (?, ?, ?, ?)",
                    order2Id, tenant.orgId(), "ORD-2-" + UUID.randomUUID().toString().substring(0, 8), "PENDING");

            // Reservation 1 for Order 1
            jdbcTemplate.update("INSERT INTO reservations (id, organization_id, order_id, status) VALUES (?, ?, ?, ?)",
                    res1Id, tenant.orgId(), order1Id, "ACTIVE");

            // 1. Mismatched order_id: reservation res1 belongs to order1, but allocation specifies order2
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO reservation_allocations (id, organization_id, reservation_id, order_id, order_item_id, product_id, warehouse_id, quantity) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), tenant.orgId(), res1Id, order2Id, item1Id, tenant.prodId(), tenant.whId(), 5L),
                    "23503",
                    "fk_reservation_allocations_reservation"
            );

            // 2. Mismatched product_id: item1Id is for prodId, but allocation specifies prod2Id
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO reservation_allocations (id, organization_id, reservation_id, order_id, order_item_id, product_id, warehouse_id, quantity) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), tenant.orgId(), res1Id, order1Id, item1Id, prod2Id, tenant.whId(), 5L),
                    "23503",
                    "fk_reservation_allocations_order_item"
            );
        }
    }

    // ========================================================================
    // 3. Quantity and Monetary Constraints
    // ========================================================================
    @Nested
    @DisplayName("3. Quantity and Monetary Constraints Tests")
    class QuantityAndMonetaryConstraintsTests {

        @Test
        @DisplayName("Order item quantity must be strictly positive")
        void orderItemQuantityMustBePositive() {
            TenantFixture tenant = createTenantFixture("QTY1");
            UUID orderId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO orders (id, organization_id, order_number, status) VALUES (?, ?, ?, ?)",
                    orderId, tenant.orgId(), "ORD-Q-" + UUID.randomUUID().toString().substring(0, 8), "PENDING");

            // quantity = 0 -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO order_items (id, organization_id, order_id, line_number, product_id, sku_snapshot, name_snapshot, quantity, unit_price) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), tenant.orgId(), orderId, 1, tenant.prodId(), "SKU", "Name", 0L, new BigDecimal("1.0000")),
                    "23514",
                    "chk_order_items_quantity"
            );

            // quantity = -5 -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO order_items (id, organization_id, order_id, line_number, product_id, sku_snapshot, name_snapshot, quantity, unit_price) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), tenant.orgId(), orderId, 2, tenant.prodId(), "SKU", "Name", -5L, new BigDecimal("1.0000")),
                    "23514",
                    "chk_order_items_quantity"
            );
        }

        @Test
        @DisplayName("Reservation allocation quantity must be strictly positive")
        void reservationAllocationQuantityMustBePositive() {
            TenantFixture tenant = createTenantFixture("QTY2");
            UUID orderId = UUID.randomUUID();
            UUID resId = UUID.randomUUID();
            UUID itemId = UUID.randomUUID();

            jdbcTemplate.update("INSERT INTO orders (id, organization_id, order_number, status) VALUES (?, ?, ?, ?)",
                    orderId, tenant.orgId(), "ORD-RA-" + UUID.randomUUID().toString().substring(0, 8), "PENDING");
            jdbcTemplate.update("INSERT INTO order_items (id, organization_id, order_id, line_number, product_id, sku_snapshot, name_snapshot, quantity, unit_price) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    itemId, tenant.orgId(), orderId, 1, tenant.prodId(), "SKU", "Name", 10L, new BigDecimal("1.0000"));
            jdbcTemplate.update("INSERT INTO reservations (id, organization_id, order_id, status) VALUES (?, ?, ?, ?)",
                    resId, tenant.orgId(), orderId, "ACTIVE");

            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO reservation_allocations (id, organization_id, reservation_id, order_id, order_item_id, product_id, warehouse_id, quantity) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), tenant.orgId(), resId, orderId, itemId, tenant.prodId(), tenant.whId(), 0L),
                    "23514",
                    "chk_reservation_allocations_quantity"
            );
        }

        @Test
        @DisplayName("Transfer item quantity must be strictly positive")
        void transferItemQuantityMustBePositive() {
            TenantFixture tenant = createTenantFixture("QTY3");
            UUID wh2Id = UUID.randomUUID();
            UUID transferId = UUID.randomUUID();

            String wh2Code = ("WH2-" + UUID.randomUUID().toString().substring(0, 6)).toUpperCase();
            jdbcTemplate.update("INSERT INTO warehouses (id, organization_id, code, normalized_code, name, priority) VALUES (?, ?, ?, ?, ?, ?)",
                    wh2Id, tenant.orgId(), wh2Code, wh2Code, "Warehouse 2", 2);
            jdbcTemplate.update("INSERT INTO transfers (id, organization_id, source_warehouse_id, destination_warehouse_id) VALUES (?, ?, ?, ?)",
                    transferId, tenant.orgId(), tenant.whId(), wh2Id);

            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO transfer_items (id, organization_id, transfer_id, product_id, quantity) VALUES (?, ?, ?, ?, ?)",
                            UUID.randomUUID(), tenant.orgId(), transferId, tenant.prodId(), 0L),
                    "23514",
                    "chk_transfer_items_quantity"
            );
        }

        @Test
        @DisplayName("Order item unit price cannot be negative; zero is accepted")
        void orderItemUnitPriceCannotBeNegative() {
            TenantFixture tenant = createTenantFixture("MON1");
            UUID orderId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO orders (id, organization_id, order_number, status) VALUES (?, ?, ?, ?)",
                    orderId, tenant.orgId(), "ORD-M-" + UUID.randomUUID().toString().substring(0, 8), "PENDING");

            // Negative price -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO order_items (id, organization_id, order_id, line_number, product_id, sku_snapshot, name_snapshot, quantity, unit_price) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), tenant.orgId(), orderId, 1, tenant.prodId(), "SKU", "Name", 1L, new BigDecimal("-0.0100")),
                    "23514",
                    "chk_order_items_unit_price"
            );

            // Zero price -> accepted (e.g., promotional item)
            int ok = jdbcTemplate.update(
                    "INSERT INTO order_items (id, organization_id, order_id, line_number, product_id, sku_snapshot, name_snapshot, quantity, unit_price) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), tenant.orgId(), orderId, 2, tenant.prodId(), "SKU", "Promo Item", 1L, new BigDecimal("0.0000"));
            assertThat(ok).isEqualTo(1);
        }

        @Test
        @DisplayName("Order total amount cannot be negative; zero is accepted")
        void orderTotalAmountCannotBeNegative() {
            TenantFixture tenant = createTenantFixture("MON2");

            // Negative total amount -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO orders (id, organization_id, order_number, status, total_amount) VALUES (?, ?, ?, ?, ?)",
                            UUID.randomUUID(), tenant.orgId(), "ORD-T-" + UUID.randomUUID().toString().substring(0, 8), "PENDING", new BigDecimal("-10.0000")),
                    "23514",
                    "chk_orders_total_amount"
            );

            // Zero total amount -> accepted
            int ok = jdbcTemplate.update(
                    "INSERT INTO orders (id, organization_id, order_number, status, total_amount) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), tenant.orgId(), "ORD-T2-" + UUID.randomUUID().toString().substring(0, 8), "PENDING", new BigDecimal("0.0000"));
            assertThat(ok).isEqualTo(1);
        }
    }

    // ========================================================================
    // 4. Lifecycle and Domain Constraints
    // ========================================================================
    @Nested
    @DisplayName("4. Lifecycle and Domain Constraints Tests")
    class LifecycleAndDomainConstraintsTests {

        @Test
        @DisplayName("Rejects invalid order statuses")
        void rejectsInvalidOrderStatus() {
            TenantFixture tenant = createTenantFixture("LC1");

            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO orders (id, organization_id, order_number, status) VALUES (?, ?, ?, ?)",
                            UUID.randomUUID(), tenant.orgId(), "ORD-S-" + UUID.randomUUID().toString().substring(0, 8), "SHIPPED"),
                    "23514",
                    "chk_orders_status"
            );
        }

        @Test
        @DisplayName("Rejects invalid reservation statuses")
        void rejectsInvalidReservationStatus() {
            TenantFixture tenant = createTenantFixture("LC2");
            UUID orderId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO orders (id, organization_id, order_number, status) VALUES (?, ?, ?, ?)",
                    orderId, tenant.orgId(), "ORD-R-" + UUID.randomUUID().toString().substring(0, 8), "PENDING");

            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO reservations (id, organization_id, order_id, status) VALUES (?, ?, ?, ?)",
                            UUID.randomUUID(), tenant.orgId(), orderId, "CANCELLED"),
                    "23514",
                    "chk_reservations_status"
            );
        }

        @Test
        @DisplayName("Rejects invalid organization membership roles")
        void rejectsInvalidMembershipRole() {
            UUID orgId = UUID.randomUUID();
            UUID userId = UUID.randomUUID();
            String roleEmail = "roleuser_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
            jdbcTemplate.update("INSERT INTO organizations (id, name) VALUES (?, ?)", orgId, "Role-Org");
            jdbcTemplate.update("INSERT INTO users (id, email, normalized_email) VALUES (?, ?, ?)",
                    userId, roleEmail, roleEmail);

            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO organization_memberships (id, organization_id, user_id, role) VALUES (?, ?, ?, ?)",
                            UUID.randomUUID(), orgId, userId, "SUPERUSER"),
                    "23514",
                    "chk_organization_memberships_role"
            );
        }

        @Test
        @DisplayName("Transaction reference semantics reject incomplete or unsupported pairs")
        void rejectsInvalidTransactionReferencePairs() {
            TenantFixture tenant = createTenantFixture("REF1");

            // 1. Only reference_type populated -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_transactions (id, organization_id, transaction_type, reference_type, reference_id) VALUES (?, ?, ?, ?, ?)",
                            UUID.randomUUID(), tenant.orgId(), "RESERVATION", "ORDER", null),
                    "23514",
                    "chk_inventory_transactions_reference_pair"
            );

            // 2. Only reference_id populated -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_transactions (id, organization_id, transaction_type, reference_type, reference_id) VALUES (?, ?, ?, ?, ?)",
                            UUID.randomUUID(), tenant.orgId(), "RESERVATION", null, UUID.randomUUID()),
                    "23514",
                    "chk_inventory_transactions_reference_pair"
            );

            // 3. Unsupported reference_type -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_transactions (id, organization_id, transaction_type, reference_type, reference_id) VALUES (?, ?, ?, ?, ?)",
                            UUID.randomUUID(), tenant.orgId(), "RECEIPT", "PURCHASE_ORDER", UUID.randomUUID()),
                    "23514",
                    "chk_inventory_transactions_reference_pair"
            );

            // 4. Both NULL (valid for standalone receipts/opening balances) -> accepted
            int okNull = jdbcTemplate.update(
                    "INSERT INTO inventory_transactions (id, organization_id, transaction_type, reference_type, reference_id) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), tenant.orgId(), "RECEIPT", null, null);
            assertThat(okNull).isEqualTo(1);

            // 5. Valid supported reference_types (ORDER, TRANSFER, MANUAL) -> accepted
            int okOrder = jdbcTemplate.update(
                    "INSERT INTO inventory_transactions (id, organization_id, transaction_type, reference_type, reference_id) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), tenant.orgId(), "RESERVATION", "ORDER", UUID.randomUUID());
            assertThat(okOrder).isEqualTo(1);

            int okManual = jdbcTemplate.update(
                    "INSERT INTO inventory_transactions (id, organization_id, transaction_type, reference_type, reference_id) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), tenant.orgId(), "ADJUSTMENT", "MANUAL", UUID.randomUUID());
            assertThat(okManual).isEqualTo(1);
        }

        @Test
        @DisplayName("Rejects multiple reservations for the same order within an organization")
        void rejectsMultipleReservationsForSameOrder() {
            TenantFixture tenant = createTenantFixture("RESUNIQ");
            UUID orderId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO orders (id, organization_id, order_number, status) VALUES (?, ?, ?, ?)",
                    orderId, tenant.orgId(), "ORD-U-" + UUID.randomUUID().toString().substring(0, 8), "PENDING");

            // First reservation succeeds
            int firstInserted = jdbcTemplate.update("INSERT INTO reservations (id, organization_id, order_id, status) VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), tenant.orgId(), orderId, "ACTIVE");
            assertThat(firstInserted).isEqualTo(1);

            // Second reservation for same order must be rejected by unique constraint
            assertSqlException(
                    () -> jdbcTemplate.update("INSERT INTO reservations (id, organization_id, order_id, status) VALUES (?, ?, ?, ?)",
                            UUID.randomUUID(), tenant.orgId(), orderId, "ACTIVE"),
                    "23505",
                    "uq_reservations_org_order"
            );
        }

        @Test
        @DisplayName("Rejects ledger entries with zero on_hand_delta and zero reserved_delta")
        void rejectsZeroDeltaLedgerEntries() {
            TenantFixture tenant = createTenantFixture("ZERODELTA");
            UUID txId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO inventory_transactions (id, organization_id, transaction_type) VALUES (?, ?, ?)",
                    txId, tenant.orgId(), "ADJUSTMENT");

            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), tenant.orgId(), txId, 1, tenant.prodId(), tenant.whId(), 0L, 0L),
                    "23514",
                    "chk_inventory_ledger_entries_non_zero_delta"
            );
        }
    }

    // ========================================================================
    // 5. Transfer Ledger Integrity
    // ========================================================================
    @Nested
    @DisplayName("5. Transfer Ledger Integrity Tests")
    class TransferLedgerIntegrityTests {

        private record TransferFixture(UUID orgId, UUID transferItemId, UUID txId, UUID prodId, UUID whSrcId, UUID whDestId) {}

        private TransferFixture setupTransferFixture() {
            TenantFixture tenant = createTenantFixture("XFER");
            UUID whDestId = UUID.randomUUID();
            UUID transferId = UUID.randomUUID();
            UUID transferItemId = UUID.randomUUID();
            UUID txId = UUID.randomUUID();

            String whDestCode = ("WH-DEST-" + UUID.randomUUID().toString().substring(0, 6)).toUpperCase();
            jdbcTemplate.update("INSERT INTO warehouses (id, organization_id, code, normalized_code, name, priority) VALUES (?, ?, ?, ?, ?, ?)",
                    whDestId, tenant.orgId(), whDestCode, whDestCode, "Destination WH", 2);
            jdbcTemplate.update("INSERT INTO transfers (id, organization_id, source_warehouse_id, destination_warehouse_id) VALUES (?, ?, ?, ?)",
                    transferId, tenant.orgId(), tenant.whId(), whDestId);
            jdbcTemplate.update("INSERT INTO transfer_items (id, organization_id, transfer_id, product_id, quantity) VALUES (?, ?, ?, ?, ?)",
                    transferItemId, tenant.orgId(), transferId, tenant.prodId(), 15L);
            jdbcTemplate.update("INSERT INTO inventory_transactions (id, organization_id, transaction_type, reference_type, reference_id) VALUES (?, ?, ?, ?, ?)",
                    txId, tenant.orgId(), "TRANSFER", "TRANSFER", transferId);

            return new TransferFixture(tenant.orgId(), transferItemId, txId, tenant.prodId(), tenant.whId(), whDestId);
        }

        @Test
        @DisplayName("TRANSFER OUT entries require negative on_hand_delta and zero reserved_delta")
        void transferOutRequiresNegativeOnHandAndZeroReserved() {
            TransferFixture fixture = setupTransferFixture();

            // OUT with positive on_hand_delta -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, transfer_item_id, transfer_direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), fixture.orgId(), fixture.txId(), 1, fixture.prodId(), fixture.whSrcId(), 15L, 0L, fixture.transferItemId(), "OUT"),
                    "23514",
                    "chk_inventory_ledger_entries_transfer_out"
            );

            // OUT with non-zero reserved_delta -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, transfer_item_id, transfer_direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), fixture.orgId(), fixture.txId(), 2, fixture.prodId(), fixture.whSrcId(), -15L, -5L, fixture.transferItemId(), "OUT"),
                    "23514",
                    "chk_inventory_ledger_entries_transfer_out"
            );

            // Valid OUT entry -> accepted
            int ok = jdbcTemplate.update(
                    "INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, transfer_item_id, transfer_direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), fixture.orgId(), fixture.txId(), 1, fixture.prodId(), fixture.whSrcId(), -15L, 0L, fixture.transferItemId(), "OUT");
            assertThat(ok).isEqualTo(1);
        }

        @Test
        @DisplayName("TRANSFER IN entries require positive on_hand_delta and zero reserved_delta")
        void transferInRequiresPositiveOnHandAndZeroReserved() {
            TransferFixture fixture = setupTransferFixture();

            // IN with negative on_hand_delta -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, transfer_item_id, transfer_direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), fixture.orgId(), fixture.txId(), 1, fixture.prodId(), fixture.whDestId(), -15L, 0L, fixture.transferItemId(), "IN"),
                    "23514",
                    "chk_inventory_ledger_entries_transfer_in"
            );

            // IN with non-zero reserved_delta -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, transfer_item_id, transfer_direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), fixture.orgId(), fixture.txId(), 2, fixture.prodId(), fixture.whDestId(), 15L, 2L, fixture.transferItemId(), "IN"),
                    "23514",
                    "chk_inventory_ledger_entries_transfer_in"
            );

            // Valid IN entry -> accepted
            int ok = jdbcTemplate.update(
                    "INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, transfer_item_id, transfer_direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), fixture.orgId(), fixture.txId(), 1, fixture.prodId(), fixture.whDestId(), 15L, 0L, fixture.transferItemId(), "IN");
            assertThat(ok).isEqualTo(1);
        }

        @Test
        @DisplayName("Rejects invalid transfer directions and incomplete transfer references")
        void rejectsInvalidTransferDirectionsAndIncompletePairs() {
            TransferFixture fixture = setupTransferFixture();

            // transfer_item_id populated without direction -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, transfer_item_id, transfer_direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), fixture.orgId(), fixture.txId(), 1, fixture.prodId(), fixture.whSrcId(), -15L, 0L, fixture.transferItemId(), null),
                    "23514",
                    "chk_inventory_ledger_entries_transfer_pair"
            );

            // direction populated without transfer_item_id -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, transfer_item_id, transfer_direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), fixture.orgId(), fixture.txId(), 2, fixture.prodId(), fixture.whSrcId(), -15L, 0L, null, "OUT"),
                    "23514",
                    "chk_inventory_ledger_entries_transfer_pair"
            );

            // invalid direction value -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, transfer_item_id, transfer_direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), fixture.orgId(), fixture.txId(), 3, fixture.prodId(), fixture.whSrcId(), -15L, 0L, fixture.transferItemId(), "LATERAL"),
                    "23514",
                    "chk_inventory_ledger_entries_transfer_direction"
            );
        }

        @Test
        @DisplayName("Rejects transfers between identical source and destination warehouses")
        void rejectsSelfTransfer() {
            TenantFixture tenant = createTenantFixture("SELFXFER");
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO transfers (id, organization_id, source_warehouse_id, destination_warehouse_id) VALUES (?, ?, ?, ?)",
                            UUID.randomUUID(), tenant.orgId(), tenant.whId(), tenant.whId()),
                    "23514",
                    "chk_transfers_distinct_warehouses"
            );
        }

        @Test
        @DisplayName("Rejects duplicate transfer OUT ledger entries for the same transfer item")
        void rejectsDuplicateTransferOutLedgerEntry() {
            TransferFixture fixture = setupTransferFixture();

            // First OUT entry succeeds
            int ok1 = jdbcTemplate.update(
                    "INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, transfer_item_id, transfer_direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), fixture.orgId(), fixture.txId(), 1, fixture.prodId(), fixture.whSrcId(), -15L, 0L, fixture.transferItemId(), "OUT");
            assertThat(ok1).isEqualTo(1);

            // Second OUT entry for the same transfer item is rejected by partial unique index idx_ledger_transfer_out
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, transfer_item_id, transfer_direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), fixture.orgId(), fixture.txId(), 2, fixture.prodId(), fixture.whSrcId(), -15L, 0L, fixture.transferItemId(), "OUT"),
                    "23505",
                    "idx_ledger_transfer_out"
            );
        }

        @Test
        @DisplayName("Rejects duplicate transfer IN ledger entries for the same transfer item")
        void rejectsDuplicateTransferInLedgerEntry() {
            TransferFixture fixture = setupTransferFixture();

            // First IN entry succeeds
            int ok1 = jdbcTemplate.update(
                    "INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, transfer_item_id, transfer_direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), fixture.orgId(), fixture.txId(), 1, fixture.prodId(), fixture.whDestId(), 15L, 0L, fixture.transferItemId(), "IN");
            assertThat(ok1).isEqualTo(1);

            // Second IN entry for the same transfer item is rejected by partial unique index idx_ledger_transfer_in
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, transfer_item_id, transfer_direction) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            UUID.randomUUID(), fixture.orgId(), fixture.txId(), 2, fixture.prodId(), fixture.whDestId(), 15L, 0L, fixture.transferItemId(), "IN"),
                    "23505",
                    "idx_ledger_transfer_in"
            );
        }
    }

    // ========================================================================
    // 6. Append-Only Enforcement (V2 Triggers)
    // ========================================================================
    @Nested
    @DisplayName("6. Append-Only Enforcement Tests (V2 Statement-Level Triggers)")
    class AppendOnlyEnforcementTests {

        @Test
        @DisplayName("inventory_ledger_entries permits INSERT and strictly rejects UPDATE, DELETE, TRUNCATE")
        void inventoryLedgerEntriesImmutability() {
            TenantFixture tenant = createTenantFixture("IMMUT-LED");
            UUID txId = UUID.randomUUID();
            UUID entryId = UUID.randomUUID();

            jdbcTemplate.update("INSERT INTO inventory_transactions (id, organization_id, transaction_type) VALUES (?, ?, ?)",
                    txId, tenant.orgId(), "RECEIPT");

            // 1. INSERT succeeds
            int inserted = jdbcTemplate.update(
                    "INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    entryId, tenant.orgId(), txId, 1, tenant.prodId(), tenant.whId(), 50L, 0L);
            assertThat(inserted).isEqualTo(1);

            // 2. UPDATE with matching rows fails (SQLSTATE 23001)
            assertSqlException(
                    () -> jdbcTemplate.update("UPDATE inventory_ledger_entries SET on_hand_delta = 99 WHERE id = ?", entryId),
                    "23001",
                    "Table inventory_ledger_entries is append-only: UPDATE operations are strictly prohibited"
            );

            // 3. UPDATE matching 0 rows fails (statement-level trigger)
            assertSqlException(
                    () -> jdbcTemplate.update("UPDATE inventory_ledger_entries SET on_hand_delta = 99 WHERE 1 = 0"),
                    "23001",
                    "Table inventory_ledger_entries is append-only: UPDATE operations are strictly prohibited"
            );

            // 4. DELETE with matching rows fails (SQLSTATE 23001)
            assertSqlException(
                    () -> jdbcTemplate.update("DELETE FROM inventory_ledger_entries WHERE id = ?", entryId),
                    "23001",
                    "Table inventory_ledger_entries is append-only: DELETE operations are strictly prohibited"
            );

            // 5. DELETE matching 0 rows fails (statement-level trigger)
            assertSqlException(
                    () -> jdbcTemplate.update("DELETE FROM inventory_ledger_entries WHERE 1 = 0"),
                    "23001",
                    "Table inventory_ledger_entries is append-only: DELETE operations are strictly prohibited"
            );

            // 6. TRUNCATE fails (SQLSTATE 23001)
            assertSqlException(
                    () -> jdbcTemplate.execute("TRUNCATE TABLE inventory_ledger_entries"),
                    "23001",
                    "Table inventory_ledger_entries is append-only: TRUNCATE operations are strictly prohibited"
            );
        }

        @Test
        @DisplayName("audit_logs permits INSERT and strictly rejects UPDATE, DELETE, TRUNCATE")
        void auditLogsImmutability() {
            TenantFixture tenant = createTenantFixture("IMMUT-AUD");
            UUID auditId = UUID.randomUUID();

            // 1. INSERT succeeds
            int inserted = jdbcTemplate.update(
                    "INSERT INTO audit_logs (id, organization_id, action, entity_type) VALUES (?, ?, ?, ?)",
                    auditId, tenant.orgId(), "TEST_EVENT", "order");
            assertThat(inserted).isEqualTo(1);

            // 2. UPDATE with matching rows fails (SQLSTATE 23001)
            assertSqlException(
                    () -> jdbcTemplate.update("UPDATE audit_logs SET action = 'MODIFIED_EVENT' WHERE id = ?", auditId),
                    "23001",
                    "Table audit_logs is append-only: UPDATE operations are strictly prohibited"
            );

            // 3. UPDATE matching 0 rows fails (statement-level trigger)
            assertSqlException(
                    () -> jdbcTemplate.update("UPDATE audit_logs SET action = 'MODIFIED_EVENT' WHERE 1 = 0"),
                    "23001",
                    "Table audit_logs is append-only: UPDATE operations are strictly prohibited"
            );

            // 4. DELETE with matching rows fails (SQLSTATE 23001)
            assertSqlException(
                    () -> jdbcTemplate.update("DELETE FROM audit_logs WHERE id = ?", auditId),
                    "23001",
                    "Table audit_logs is append-only: DELETE operations are strictly prohibited"
            );

            // 5. DELETE matching 0 rows fails (statement-level trigger)
            assertSqlException(
                    () -> jdbcTemplate.update("DELETE FROM audit_logs WHERE 1 = 0"),
                    "23001",
                    "Table audit_logs is append-only: DELETE operations are strictly prohibited"
            );

            // 6. TRUNCATE fails (SQLSTATE 23001)
            assertSqlException(
                    () -> jdbcTemplate.execute("TRUNCATE TABLE audit_logs"),
                    "23001",
                    "Table audit_logs is append-only: TRUNCATE operations are strictly prohibited"
            );
        }
    }

    // ========================================================================
    // 7. Idempotency Records Integrity Tests
    // ========================================================================
    @Nested
    @DisplayName("7. Idempotency Records Integrity Tests")
    class IdempotencyRecordsIntegrityTests {

        @Test
        @DisplayName("Idempotency records enforce response pair coupling and HTTP status code range")
        void idempotencyResponseIntegrity() {
            TenantFixture tenant = createTenantFixture("IDEMPRES");

            // 1. Incomplete pair: response_status without response_body -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO idempotency_records (id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, response_status, response_body, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                            UUID.randomUUID(), tenant.orgId(), "USER", tenant.userId(), "POST:/api/orders", "KEY-1", "hash1", 200, null),
                    "23514",
                    "chk_idempotency_records_response_pair"
            );

            // 2. Incomplete pair: response_body without response_status -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO idempotency_records (id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, response_status, response_body, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                            UUID.randomUUID(), tenant.orgId(), "USER", tenant.userId(), "POST:/api/orders", "KEY-2", "hash2", null, "{\"ok\":true}"),
                    "23514",
                    "chk_idempotency_records_response_pair"
            );

            // 3. Status code < 100 (99) -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO idempotency_records (id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, response_status, response_body, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                            UUID.randomUUID(), tenant.orgId(), "USER", tenant.userId(), "POST:/api/orders", "KEY-3", "hash3", 99, "{\"error\":true}"),
                    "23514",
                    "chk_idempotency_records_status_code"
            );

            // 4. Status code > 599 (600) -> rejected
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO idempotency_records (id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, response_status, response_body, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                            UUID.randomUUID(), tenant.orgId(), "USER", tenant.userId(), "POST:/api/orders", "KEY-4", "hash4", 600, "{\"error\":true}"),
                    "23514",
                    "chk_idempotency_records_status_code"
            );

            // 5. Valid pending record with both response fields NULL -> accepted
            int okPending = jdbcTemplate.update(
                    "INSERT INTO idempotency_records (id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, response_status, response_body, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                    UUID.randomUUID(), tenant.orgId(), "USER", tenant.userId(), "POST:/api/orders", "KEY-PENDING", "hashPending", null, null);
            assertThat(okPending).isEqualTo(1);

            // 6. Valid completed record with both response fields populated -> accepted
            int okCompleted = jdbcTemplate.update(
                    "INSERT INTO idempotency_records (id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, response_status, response_body, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                    UUID.randomUUID(), tenant.orgId(), "USER", tenant.userId(), "POST:/api/orders", "KEY-COMPLETED", "hashCompleted", 201, "{\"status\":\"created\"}");
            assertThat(okCompleted).isEqualTo(1);
        }

        @Test
        @DisplayName("Idempotency records enforce 5-dimension scope uniqueness and permit key reuse across dimensions")
        void idempotencyScopeUniqueness() {
            TenantFixture tenant = createTenantFixture("IDEMPSCOPE");
            UUID user2Id = UUID.randomUUID();
            String user2Email = "idemp_user2_" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
            jdbcTemplate.update("INSERT INTO users (id, email, normalized_email) VALUES (?, ?, ?)",
                    user2Id, user2Email, user2Email);
            jdbcTemplate.update("INSERT INTO organization_memberships (organization_id, user_id, role) VALUES (?, ?, ?)",
                    tenant.orgId(), user2Id, "OPERATOR");

            UUID id1 = UUID.randomUUID();
            String sharedKey = "REQ-KEY-" + UUID.randomUUID().toString().substring(0, 8);
            String scope1 = "POST:/api/orders";
            String scope2 = "POST:/api/reservations";

            // 1. Initial insert of (orgId, 'USER', userId, scope1, sharedKey) -> succeeds
            int inserted = jdbcTemplate.update(
                    "INSERT INTO idempotency_records (id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                    id1, tenant.orgId(), "USER", tenant.userId(), scope1, sharedKey, "hash1");
            assertThat(inserted).isEqualTo(1);

            // 2. Exact same 5-part scope duplicate -> rejected with SQLSTATE 23505 and uq_idempotency_records_scope
            assertSqlException(
                    () -> jdbcTemplate.update(
                            "INSERT INTO idempotency_records (id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                            UUID.randomUUID(), tenant.orgId(), "USER", tenant.userId(), scope1, sharedKey, "hash1"),
                    "23505",
                    "uq_idempotency_records_scope"
            );

            // 3. Same key with different principal_id (user2Id) -> succeeds
            int diffUserInserted = jdbcTemplate.update(
                    "INSERT INTO idempotency_records (id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                    UUID.randomUUID(), tenant.orgId(), "USER", user2Id, scope1, sharedKey, "hashUser2");
            assertThat(diffUserInserted).isEqualTo(1);

            // 4. Same key and user with different operation_scope (scope2) -> succeeds
            int diffScopeInserted = jdbcTemplate.update(
                    "INSERT INTO idempotency_records (id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP + INTERVAL '7 days')",
                    UUID.randomUUID(), tenant.orgId(), "USER", tenant.userId(), scope2, sharedKey, "hashScope2");
            assertThat(diffScopeInserted).isEqualTo(1);
        }
    }
}
