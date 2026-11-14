package com.stockguard.reservation;

import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.dto.OpeningBalanceRequest;
import com.stockguard.inventory.dto.ReconciliationReport;
import com.stockguard.inventory.repository.InventoryBalanceRepository;
import com.stockguard.inventory.service.InventoryReconciliationService;
import com.stockguard.inventory.service.InventoryService;
import com.stockguard.order.domain.OrderStatus;
import com.stockguard.order.dto.CreateOrderRequest;
import com.stockguard.order.dto.OrderItemRequest;
import com.stockguard.order.dto.OrderResponse;
import com.stockguard.order.repository.OrderRepository;
import com.stockguard.order.service.OrderService;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.product.domain.Product;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.reservation.config.ReservationProperties;
import com.stockguard.reservation.domain.Reservation;
import com.stockguard.reservation.domain.ReservationStatus;
import com.stockguard.reservation.dto.ExpirationBatchResult;
import com.stockguard.reservation.dto.ReservationResponse;
import com.stockguard.reservation.repository.ReservationRepository;
import com.stockguard.reservation.service.ReservationExpirationService;
import com.stockguard.reservation.service.ReservationService;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.warehouse.domain.Warehouse;
import com.stockguard.warehouse.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class ReservationExpirationCoreIntegrationTest {

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
        registry.add("stockguard.security.bootstrap.enabled", () -> "false");
        // Ensure background worker doesn't run during test execution; tests drive sweeps deterministically
        registry.add("stockguard.reservation.expiration.enabled", () -> "false");
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationExpirationService expirationService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryReconciliationService reconciliationService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private InventoryBalanceRepository balanceRepository;

    @Autowired
    private ReservationProperties reservationProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID orgId;
    private UUID actorUserId;
    private Warehouse warehouse1;
    private Warehouse warehouse2;
    private Product productA;
    private Product productB;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.create("Expiration Core Org " + UUID.randomUUID()));
        orgId = org.getId();

        User actor = userRepository.save(User.create("exp.user." + UUID.randomUUID() + "@stockguard.com", "hash"));
        actorUserId = actor.getId();
        membershipRepository.save(OrganizationMembership.create(orgId, actorUserId, Role.ADMIN));

        warehouse1 = warehouseRepository.save(new Warehouse(orgId, "WH-EXP-1-" + UUID.randomUUID().toString().substring(0, 5), "WH 1", "Loc", 10));
        warehouse2 = warehouseRepository.save(new Warehouse(orgId, "WH-EXP-2-" + UUID.randomUUID().toString().substring(0, 5), "WH 2", "Loc", 20));

        productA = productRepository.save(new Product(orgId, "PROD-EXP-A-" + UUID.randomUUID().toString().substring(0, 5), "Product A", null));
        productB = productRepository.save(new Product(orgId, "PROD-EXP-B-" + UUID.randomUUID().toString().substring(0, 5), "Product B", null));
    }

    // -------------------------------------------------------------------------
    // A. Expiration Correctness (Tests 1-8)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("A1-A6 & A8: Expired pending order with active reservation expires cleanly with (0, -Q) deltas and reconciles")
    void expiredPendingOrderExpiresCleanly() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Stock"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-EXP-1", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 25L, new BigDecimal("10.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, orderReq, null);
        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);

        // Simulate expiration in PostgreSQL database clock
        jdbcTemplate.update("UPDATE reservations SET expires_at = NOW() - INTERVAL '5 minutes' WHERE id = ?", res.id());

        // Run sweep
        ExpirationBatchResult result = expirationService.sweepExpiredReservations(orgId);
        assertThat(result.examinedCount()).isEqualTo(1);
        assertThat(result.successCount()).isEqualTo(1);

        // 1. Order status is EXPIRED
        var dbOrder = orderRepository.findByIdAndOrganizationId(order.id(), orgId).orElseThrow();
        assertThat(dbOrder.getStatus()).isEqualTo(OrderStatus.EXPIRED);

        // 2. Reservation status is EXPIRED
        Reservation dbRes = reservationRepository.findByIdAndOrganizationId(res.id(), orgId).orElseThrow();
        assertThat(dbRes.getStatus()).isEqualTo(ReservationStatus.EXPIRED);

        // 3 & 4. Inventory balance: on_hand unchanged (100), reserved decremented to 0
        InventoryBalance bal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(bal.getOnHand()).isEqualTo(100L);
        assertThat(bal.getReserved()).isEqualTo(0L);

        // 5 & 6. Transaction header (RELEASE, ORDER) and ledger deltas (0, -25)
        Integer txCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_transactions WHERE organization_id = ? AND reference_id = ? AND transaction_type = 'RELEASE' AND reference_type = 'ORDER'",
                Integer.class, orgId, order.id()
        );
        assertThat(txCount).isEqualTo(1);

        List<Long> deltas = jdbcTemplate.query(
                "SELECT on_hand_delta, reserved_delta FROM inventory_ledger_entries WHERE organization_id = ? AND product_id = ? ORDER BY created_at DESC, entry_number DESC LIMIT 1",
                (rs, rowNum) -> List.of(rs.getLong("on_hand_delta"), rs.getLong("reserved_delta")),
                orgId, productA.getId()
        ).get(0);
        assertThat(deltas.get(0)).isEqualTo(0L);
        assertThat(deltas.get(1)).isEqualTo(-25L);

        // 8. Reconciliation reports zero discrepancies
        ReconciliationReport report = reconciliationService.reconcile(orgId);
        assertThat(report.discrepantBalances()).isEqualTo(0);
        assertThat(report.discrepancies()).isEmpty();
    }

    @Test
    @DisplayName("A7: Split allocations across multiple warehouses are released correctly upon expiration")
    void splitAllocationsAcrossWarehousesReleasedCorrectly() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 15L, "WH1 Stock"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse2.getId(), 25L, "WH2 Stock"), null);

        // Request 30 units: allocates 15 from WH1 (priority 10) and 15 from WH2 (priority 20)
        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-EXP-SPLIT", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 30L, new BigDecimal("10.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, orderReq, null);
        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);

        // Mark expired
        jdbcTemplate.update("UPDATE reservations SET expires_at = NOW() - INTERVAL '10 seconds' WHERE id = ?", res.id());

        // Run sweep
        ExpirationBatchResult result = expirationService.sweepExpiredReservations(orgId);
        assertThat(result.successCount()).isEqualTo(1);

        // Check balances: WH1 reserved=0, on_hand=15; WH2 reserved=0, on_hand=25
        InventoryBalance bal1 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(bal1.getOnHand()).isEqualTo(15L);
        assertThat(bal1.getReserved()).isEqualTo(0L);

        InventoryBalance bal2 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse2.getId()).orElseThrow();
        assertThat(bal2.getOnHand()).isEqualTo(25L);
        assertThat(bal2.getReserved()).isEqualTo(0L);

        // Reconcile
        ReconciliationReport report = reconciliationService.reconcile(orgId);
        assertThat(report.discrepantBalances()).isEqualTo(0);
        assertThat(report.discrepancies()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // B. Non-Expiration Cases (Tests 9-15)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("B9: Confirmed order with expires_at = NULL remains untouched")
    void confirmedOrderWithNullExpiresAtRemainsUntouched() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Stock"), null);

        OrderResponse order = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("CUST-CONF", null, "USD", List.of(new OrderItemRequest(productA.getId(), 10L, new BigDecimal("10.0000")))), null);
        reservationService.createReservation(orgId, actorUserId, order.id(), null);
        orderService.confirmOrder(orgId, actorUserId, order.id(), null);

        ExpirationBatchResult result = expirationService.sweepExpiredReservations(orgId);
        assertThat(result.examinedCount()).isEqualTo(0);

        var dbOrder = orderRepository.findByIdAndOrganizationId(order.id(), orgId).orElseThrow();
        assertThat(dbOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);

        InventoryBalance bal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(bal.getReserved()).isEqualTo(10L);
    }

    @Test
    @DisplayName("B10: Pending order with future expiry remains untouched")
    void pendingOrderWithFutureExpiryRemainsUntouched() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Stock"), null);

        OrderResponse order = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("CUST-FUTURE", null, "USD", List.of(new OrderItemRequest(productA.getId(), 10L, new BigDecimal("10.0000")))), null);
        reservationService.createReservation(orgId, actorUserId, order.id(), null);

        // Ensure expires_at is in the future
        jdbcTemplate.update("UPDATE reservations SET expires_at = NOW() + INTERVAL '10 minutes' WHERE order_id = ?", order.id());

        ExpirationBatchResult result = expirationService.sweepExpiredReservations(orgId);
        assertThat(result.examinedCount()).isEqualTo(0);

        var dbOrder = orderRepository.findByIdAndOrganizationId(order.id(), orgId).orElseThrow();
        assertThat(dbOrder.getStatus()).isEqualTo(OrderStatus.PENDING);

        InventoryBalance bal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(bal.getReserved()).isEqualTo(10L);
    }

    @Test
    @DisplayName("B11: Cancelled order remains untouched")
    void cancelledOrderRemainsUntouched() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Stock"), null);

        OrderResponse order = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("CUST-CANC", null, "USD", List.of(new OrderItemRequest(productA.getId(), 10L, new BigDecimal("10.0000")))), null);
        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);
        orderService.cancelOrder(orgId, actorUserId, order.id(), null);

        // Set past expiry on cancelled reservation
        jdbcTemplate.update("UPDATE reservations SET expires_at = NOW() - INTERVAL '5 minutes' WHERE id = ?", res.id());

        ExpirationBatchResult result = expirationService.sweepExpiredReservations(orgId);
        assertThat(result.examinedCount()).isEqualTo(0);

        var dbOrder = orderRepository.findByIdAndOrganizationId(order.id(), orgId).orElseThrow();
        assertThat(dbOrder.getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("B12: Fulfilled order remains untouched")
    void fulfilledOrderRemainsUntouched() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Stock"), null);

        OrderResponse order = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("CUST-FULF", null, "USD", List.of(new OrderItemRequest(productA.getId(), 10L, new BigDecimal("10.0000")))), null);
        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);
        orderService.confirmOrder(orgId, actorUserId, order.id(), null);
        orderService.fulfillOrder(orgId, actorUserId, order.id(), null);

        // Set past expiry on consumed reservation
        jdbcTemplate.update("UPDATE reservations SET expires_at = NOW() - INTERVAL '5 minutes' WHERE id = ?", res.id());

        ExpirationBatchResult result = expirationService.sweepExpiredReservations(orgId);
        assertThat(result.examinedCount()).isEqualTo(0);

        var dbOrder = orderRepository.findByIdAndOrganizationId(order.id(), orgId).orElseThrow();
        assertThat(dbOrder.getStatus()).isEqualTo(OrderStatus.FULFILLED);
    }

    @Test
    @DisplayName("B13: Already expired order is not processed again")
    void alreadyExpiredOrderNotProcessedAgain() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Stock"), null);

        OrderResponse order = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("CUST-EXP-TWICE", null, "USD", List.of(new OrderItemRequest(productA.getId(), 10L, new BigDecimal("10.0000")))), null);
        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);
        jdbcTemplate.update("UPDATE reservations SET expires_at = NOW() - INTERVAL '5 minutes' WHERE id = ?", res.id());

        // Sweep 1 expires it
        ExpirationBatchResult r1 = expirationService.sweepExpiredReservations(orgId);
        assertThat(r1.successCount()).isEqualTo(1);

        // Sweep 2 finds 0 candidates
        ExpirationBatchResult r2 = expirationService.sweepExpiredReservations(orgId);
        assertThat(r2.examinedCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("B14 & B15: Order without an active reservation is not processed and no zero-delta ledgers are created")
    void orderWithoutActiveReservationNotProcessed() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Stock"), null);

        OrderResponse order = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("CUST-NO-RES", null, "USD", List.of(new OrderItemRequest(productA.getId(), 10L, new BigDecimal("10.0000")))), null);

        Integer ledgerCountBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ?", Integer.class, orgId);

        ExpirationBatchResult result = expirationService.sweepExpiredReservations(orgId);
        assertThat(result.examinedCount()).isEqualTo(0);

        Integer ledgerCountAfter = jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ?", Integer.class, orgId);
        assertThat(ledgerCountAfter).isEqualTo(ledgerCountBefore);
    }

    // -------------------------------------------------------------------------
    // C. Transaction Behavior (Tests 16-19)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("C16-C18: Failure after partial inventory mutation rolls back candidate completely, leaving it eligible for later retry")
    void transactionalRollbackOnCandidateFailureAndSuccessfulRetry() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Stock"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-ROLLBACK", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 30L, new BigDecimal("10.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, orderReq, null);
        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);
        jdbcTemplate.update("UPDATE reservations SET expires_at = NOW() - INTERVAL '5 minutes' WHERE id = ?", res.id());

        InventoryBalance balBefore = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(balBefore.getReserved()).isEqualTo(30L);

        // Force an inconsistency on balance reserved quantity to simulate a failure during balance verification
        jdbcTemplate.update("UPDATE inventory_balances SET reserved = 0 WHERE id = ?", balBefore.getId());

        // Run sweep: Candidate encounters IllegalStateException during balance verification and rolls back
        ExpirationBatchResult result = expirationService.sweepExpiredReservations(orgId);
        assertThat(result.examinedCount()).isEqualTo(1);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.successCount()).isEqualTo(0);

        // 16. Verify complete rollback: order=PENDING, reservation=ACTIVE
        var dbOrder = orderRepository.findByIdAndOrganizationId(order.id(), orgId).orElseThrow();
        assertThat(dbOrder.getStatus()).isEqualTo(OrderStatus.PENDING);

        Reservation dbRes = reservationRepository.findByIdAndOrganizationId(res.id(), orgId).orElseThrow();
        assertThat(dbRes.getStatus()).isEqualTo(ReservationStatus.ACTIVE);

        Integer releaseLedgers = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_transactions WHERE organization_id = ? AND reference_id = ? AND transaction_type = 'RELEASE'",
                Integer.class, orgId, order.id()
        );
        assertThat(releaseLedgers).isEqualTo(0);

        // Restore reserved balance to 30 to test retry
        jdbcTemplate.update("UPDATE inventory_balances SET reserved = 30 WHERE id = ?", balBefore.getId());

        // 17 & 18. Successful retry expires candidate exactly once
        ExpirationBatchResult retryResult = expirationService.sweepExpiredReservations(orgId);
        assertThat(retryResult.examinedCount()).isEqualTo(1);
        assertThat(retryResult.successCount()).isEqualTo(1);

        InventoryBalance balAfterRetry = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(balAfterRetry.getOnHand()).isEqualTo(100L);
        assertThat(balAfterRetry.getReserved()).isEqualTo(0L);

        var finalOrder = orderRepository.findByIdAndOrganizationId(order.id(), orgId).orElseThrow();
        assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.EXPIRED);

        Reservation finalRes = reservationRepository.findByIdAndOrganizationId(res.id(), orgId).orElseThrow();
        assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    @Test
    @DisplayName("C19: Failure for one candidate does not roll back another successfully processed candidate")
    void candidateFailureDoesNotRollbackOtherCandidates() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 200L, "Stock A"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productB.getId(), warehouse1.getId(), 200L, "Stock B"), null);

        // Candidate 1 (product A, will fail due to corrupted balance)
        OrderResponse o1 = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("CUST-FAIL-1", null, "USD", List.of(new OrderItemRequest(productA.getId(), 20L, new BigDecimal("10.0000")))), null);
        ReservationResponse r1 = reservationService.createReservation(orgId, actorUserId, o1.id(), null);
        jdbcTemplate.update("UPDATE reservations SET expires_at = NOW() - INTERVAL '10 minutes' WHERE id = ?", r1.id());

        // Candidate 2 (product B, will succeed)
        OrderResponse o2 = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("CUST-SUCC-2", null, "USD", List.of(new OrderItemRequest(productB.getId(), 30L, new BigDecimal("10.0000")))), null);
        ReservationResponse r2 = reservationService.createReservation(orgId, actorUserId, o2.id(), null);
        jdbcTemplate.update("UPDATE reservations SET expires_at = NOW() - INTERVAL '5 minutes' WHERE id = ?", r2.id());

        // Corrupt product A's reserved balance so Candidate 1 fails
        InventoryBalance balA = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        jdbcTemplate.update("UPDATE inventory_balances SET reserved = 0 WHERE id = ?", balA.getId());

        ExpirationBatchResult result = expirationService.sweepExpiredReservations(orgId);
        assertThat(result.examinedCount()).isEqualTo(2);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(result.successCount()).isEqualTo(1);

        // Candidate 1 rolled back and remains PENDING / ACTIVE
        var order1 = orderRepository.findByIdAndOrganizationId(o1.id(), orgId).orElseThrow();
        assertThat(order1.getStatus()).isEqualTo(OrderStatus.PENDING);

        // Candidate 2 committed successfully and is EXPIRED
        var order2 = orderRepository.findByIdAndOrganizationId(o2.id(), orgId).orElseThrow();
        assertThat(order2.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        Reservation res2 = reservationRepository.findByIdAndOrganizationId(r2.id(), orgId).orElseThrow();
        assertThat(res2.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
    }

    // -------------------------------------------------------------------------
    // E. Batch Behavior (Tests 27-28)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("E27-E28: Batch never processes more than configured maximum and additional candidates are processed in next sweep")
    void batchNeverExceedsConfiguredMaximumAndSubsequentSweepsProcessRemainder() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 500L, "Stock"), null);

        // Set batch size to 3
        int originalBatchSize = reservationProperties.getExpiration().getBatchSize();
        reservationProperties.getExpiration().setBatchSize(3);

        try {
            // Create 5 expired orders
            for (int i = 1; i <= 5; i++) {
                OrderResponse o = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("CUST-BATCH-" + i, null, "USD", List.of(new OrderItemRequest(productA.getId(), 10L, new BigDecimal("10.0000")))), null);
                ReservationResponse r = reservationService.createReservation(orgId, actorUserId, o.id(), null);
                jdbcTemplate.update("UPDATE reservations SET expires_at = NOW() - INTERVAL '" + (10 - i) + " minutes' WHERE id = ?", r.id());
            }

            // Sweep 1: Processes exactly 3 candidates
            ExpirationBatchResult sweep1 = expirationService.sweepExpiredReservations(orgId);
            assertThat(sweep1.examinedCount()).isEqualTo(3);
            assertThat(sweep1.successCount()).isEqualTo(3);

            // Exactly 3 orders are now EXPIRED, 2 remain PENDING
            Integer expiredCountAfterSweep1 = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM orders WHERE organization_id = ? AND status = 'EXPIRED'",
                    Integer.class, orgId
            );
            assertThat(expiredCountAfterSweep1).isEqualTo(3);

            // Sweep 2: Processes the remaining 2 candidates
            ExpirationBatchResult sweep2 = expirationService.sweepExpiredReservations(orgId);
            assertThat(sweep2.examinedCount()).isEqualTo(2);
            assertThat(sweep2.successCount()).isEqualTo(2);

            Integer expiredCountAfterSweep2 = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM orders WHERE organization_id = ? AND status = 'EXPIRED'",
                    Integer.class, orgId
            );
            assertThat(expiredCountAfterSweep2).isEqualTo(5);
        } finally {
            reservationProperties.getExpiration().setBatchSize(originalBatchSize);
        }
    }
}
