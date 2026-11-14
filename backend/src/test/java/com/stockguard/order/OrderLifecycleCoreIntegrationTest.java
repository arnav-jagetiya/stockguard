package com.stockguard.order;

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
import com.stockguard.order.exception.InvalidOrderStateException;
import com.stockguard.order.repository.OrderRepository;
import com.stockguard.order.service.OrderService;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.product.domain.Product;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.reservation.domain.Reservation;
import com.stockguard.reservation.domain.ReservationStatus;
import com.stockguard.reservation.dto.ReservationResponse;
import com.stockguard.reservation.repository.ReservationRepository;
import com.stockguard.reservation.service.ReservationService;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class OrderLifecycleCoreIntegrationTest {

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
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private ReservationService reservationService;

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
    private com.stockguard.warehouse.repository.WarehouseRepository warehouseRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private InventoryBalanceRepository balanceRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID orgId;
    private UUID actorUserId;
    private com.stockguard.warehouse.domain.Warehouse warehouse1;
    private com.stockguard.warehouse.domain.Warehouse warehouse2;
    private Product productA;
    private Product productB;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.create("Lifecycle Org " + UUID.randomUUID()));
        orgId = org.getId();

        User actor = userRepository.save(User.create("lifecycle.user." + UUID.randomUUID() + "@stockguard.com", "hash"));
        actorUserId = actor.getId();
        membershipRepository.save(OrganizationMembership.create(orgId, actorUserId, Role.ADMIN));

        warehouse1 = warehouseRepository.save(new com.stockguard.warehouse.domain.Warehouse(orgId, "WH-LC-1-" + UUID.randomUUID().toString().substring(0, 5), "WH 1", "Loc", 10));
        warehouse2 = warehouseRepository.save(new com.stockguard.warehouse.domain.Warehouse(orgId, "WH-LC-2-" + UUID.randomUUID().toString().substring(0, 5), "WH 2", "Loc", 20));

        productA = productRepository.save(new Product(orgId, "PROD-LC-A-" + UUID.randomUUID().toString().substring(0, 5), "Product A", null));
        productB = productRepository.save(new Product(orgId, "PROD-LC-B-" + UUID.randomUUID().toString().substring(0, 5), "Product B", null));
    }

    @Test
    @DisplayName("Confirmation: PENDING -> CONFIRMED clears expires_at and leaves stock/ledgers untouched")
    void confirmOrderSuccessClearsExpiresAtAndDoesNotModifyBalances() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Stock A"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-CONFIRM", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 25L, new BigDecimal("10.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, orderReq, null);
        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);

        // Before confirmation: reservation has expires_at NOT NULL
        assertThat(res.expiresAt()).isNotNull();

        // Count ledger entries before confirm
        Integer ledgerCountBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ?",
                Integer.class, orgId
        );

        // Execute Confirmation
        OrderResponse confirmed = orderService.confirmOrder(orgId, actorUserId, order.id(), null);

        assertThat(confirmed.status()).isEqualTo(OrderStatus.CONFIRMED);

        // Verify reservation in DB: status is still ACTIVE, but expires_at is now NULL (non-expiring)
        Reservation dbRes = reservationRepository.findByIdAndOrganizationId(res.id(), orgId).orElseThrow();
        assertThat(dbRes.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(dbRes.getExpiresAt()).isNull();

        // Verify inventory balances untouched (on_hand = 100, reserved = 25)
        InventoryBalance bal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(bal.getOnHand()).isEqualTo(100L);
        assertThat(bal.getReserved()).isEqualTo(25L);

        // Verify zero new ledger entries inserted
        Integer ledgerCountAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ?",
                Integer.class, orgId
        );
        assertThat(ledgerCountAfter).isEqualTo(ledgerCountBefore);
    }

    @Test
    @DisplayName("Confirmation rejected if order has no active reservation or is already confirmed")
    void confirmOrderRejectionScenarios() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Stock"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-NO-RES", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 10L, new BigDecimal("10.0000")))
        );
        OrderResponse orderWithoutRes = orderService.createOrder(orgId, actorUserId, orderReq, null);

        // 1. Order without reservation rejected
        assertThatThrownBy(() -> orderService.confirmOrder(orgId, actorUserId, orderWithoutRes.id(), null))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("ACTIVE reservation");

        // Reserve it and confirm
        reservationService.createReservation(orgId, actorUserId, orderWithoutRes.id(), null);
        orderService.confirmOrder(orgId, actorUserId, orderWithoutRes.id(), null);

        // 2. Already confirmed order rejected (non-idempotent)
        assertThatThrownBy(() -> orderService.confirmOrder(orgId, actorUserId, orderWithoutRes.id(), null))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("PENDING");
    }

    @Test
    @DisplayName("Cancellation: PENDING order with ACTIVE reservation releases reserved balance and appends RELEASE ledgers")
    void cancelPendingOrderWithActiveReservation() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Stock"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-CANCEL-PEND", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 30L, new BigDecimal("10.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, orderReq, null);
        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);

        // Before cancellation: on_hand=100, reserved=30
        InventoryBalance balBefore = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(balBefore.getOnHand()).isEqualTo(100L);
        assertThat(balBefore.getReserved()).isEqualTo(30L);

        // Execute Cancellation
        OrderResponse cancelled = orderService.cancelOrder(orgId, actorUserId, order.id(), null);

        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);

        // Reservation marked RELEASED
        Reservation dbRes = reservationRepository.findByIdAndOrganizationId(res.id(), orgId).orElseThrow();
        assertThat(dbRes.getStatus()).isEqualTo(ReservationStatus.RELEASED);

        // Balances updated: on_hand unchanged (100), reserved decremented to 0
        InventoryBalance balAfter = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(balAfter.getOnHand()).isEqualTo(100L);
        assertThat(balAfter.getReserved()).isEqualTo(0L);

        // Ledger check: RELEASE transaction and delta (on_hand_delta = 0, reserved_delta = -30)
        Integer releaseTxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_transactions WHERE organization_id = ? AND reference_id = ? AND transaction_type = 'RELEASE'",
                Integer.class, orgId, order.id()
        );
        assertThat(releaseTxCount).isEqualTo(1);

        Long lastReservedDelta = jdbcTemplate.queryForObject(
                "SELECT reserved_delta FROM inventory_ledger_entries WHERE organization_id = ? AND product_id = ? ORDER BY created_at DESC, entry_number DESC LIMIT 1",
                Long.class, orgId, productA.getId()
        );
        assertThat(lastReservedDelta).isEqualTo(-30L);
    }

    @Test
    @DisplayName("Cancellation: CONFIRMED order with ACTIVE reservation releases reserved balance and appends RELEASE ledgers")
    void cancelConfirmedOrder() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Stock"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-CANCEL-CONF", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 40L, new BigDecimal("10.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, orderReq, null);
        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);
        orderService.confirmOrder(orgId, actorUserId, order.id(), null);

        // Execute Cancellation of CONFIRMED order
        OrderResponse cancelled = orderService.cancelOrder(orgId, actorUserId, order.id(), null);
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);

        // Reservation is RELEASED
        Reservation dbRes = reservationRepository.findByIdAndOrganizationId(res.id(), orgId).orElseThrow();
        assertThat(dbRes.getStatus()).isEqualTo(ReservationStatus.RELEASED);

        // Reserved balance is released back to 0, on_hand remains 100
        InventoryBalance balAfter = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(balAfter.getOnHand()).isEqualTo(100L);
        assertThat(balAfter.getReserved()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Cancellation: PENDING order without reservation transitions to CANCELLED with zero stock effects")
    void cancelPendingOrderWithoutReservation() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Stock"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-CANCEL-NO-RES", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 15L, new BigDecimal("10.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, orderReq, null);

        Integer ledgerCountBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ?",
                Integer.class, orgId
        );

        // Cancel order without reservation
        OrderResponse cancelled = orderService.cancelOrder(orgId, actorUserId, order.id(), null);
        assertThat(cancelled.status()).isEqualTo(OrderStatus.CANCELLED);

        // Verify zero reservations exist
        Integer resCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservations WHERE organization_id = ? AND order_id = ?",
                Integer.class, orgId, order.id()
        );
        assertThat(resCount).isEqualTo(0);

        // Balances untouched (on_hand=100, reserved=0)
        InventoryBalance bal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(bal.getOnHand()).isEqualTo(100L);
        assertThat(bal.getReserved()).isEqualTo(0L);

        // Ledger entries unchanged
        Integer ledgerCountAfter = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ?",
                Integer.class, orgId
        );
        assertThat(ledgerCountAfter).isEqualTo(ledgerCountBefore);
    }

    @Test
    @DisplayName("Fulfillment: CONFIRMED order -> FULFILLED decreases on_hand and reserved and consumes reservation")
    void fulfillConfirmedOrderSuccess() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Stock"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-FULFILL", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 35L, new BigDecimal("10.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, orderReq, null);
        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);
        orderService.confirmOrder(orgId, actorUserId, order.id(), null);

        // Execute Fulfillment
        OrderResponse fulfilled = orderService.fulfillOrder(orgId, actorUserId, order.id(), null);
        assertThat(fulfilled.status()).isEqualTo(OrderStatus.FULFILLED);

        // Reservation status is CONSUMED
        Reservation dbRes = reservationRepository.findByIdAndOrganizationId(res.id(), orgId).orElseThrow();
        assertThat(dbRes.getStatus()).isEqualTo(ReservationStatus.CONSUMED);

        // Inventory balances: on_hand = 100 - 35 = 65, reserved = 35 - 35 = 0
        InventoryBalance balAfter = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(balAfter.getOnHand()).isEqualTo(65L);
        assertThat(balAfter.getReserved()).isEqualTo(0L);

        // Ledger entry check: on_hand_delta = -35, reserved_delta = -35, transaction_type = 'FULFILLMENT'
        Integer fulfillTxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_transactions WHERE organization_id = ? AND reference_id = ? AND transaction_type = 'FULFILLMENT'",
                Integer.class, orgId, order.id()
        );
        assertThat(fulfillTxCount).isEqualTo(1);

        List<Long> deltas = jdbcTemplate.query(
                "SELECT on_hand_delta, reserved_delta FROM inventory_ledger_entries WHERE organization_id = ? AND product_id = ? ORDER BY created_at DESC, entry_number DESC LIMIT 1",
                (rs, rowNum) -> List.of(rs.getLong("on_hand_delta"), rs.getLong("reserved_delta")),
                orgId, productA.getId()
        ).get(0);

        assertThat(deltas.get(0)).isEqualTo(-35L);
        assertThat(deltas.get(1)).isEqualTo(-35L);
    }

    @Test
    @DisplayName("Fulfillment rejected if order is not in CONFIRMED status")
    void fulfillOrderRejectionScenarios() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Stock"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-FULFILL-PENDING", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 10L, new BigDecimal("10.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, orderReq, null);
        reservationService.createReservation(orgId, actorUserId, order.id(), null);

        // 1. PENDING order cannot be fulfilled
        assertThatThrownBy(() -> orderService.fulfillOrder(orgId, actorUserId, order.id(), null))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("CONFIRMED");

        // Cancel order
        orderService.cancelOrder(orgId, actorUserId, order.id(), null);

        // 2. CANCELLED order cannot be fulfilled
        assertThatThrownBy(() -> orderService.fulfillOrder(orgId, actorUserId, order.id(), null))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("CONFIRMED");
    }

    @Test
    @DisplayName("Terminal order protection: CANCELLED order cannot be confirmed, fulfilled, or cancelled again")
    void cancelledOrderIsTerminal() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Stock"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-TERMINAL-CANCEL", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 10L, new BigDecimal("10.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, orderReq, null);
        reservationService.createReservation(orgId, actorUserId, order.id(), null);
        orderService.cancelOrder(orgId, actorUserId, order.id(), null);

        assertThatThrownBy(() -> orderService.confirmOrder(orgId, actorUserId, order.id(), null))
                .isInstanceOf(InvalidOrderStateException.class);

        assertThatThrownBy(() -> orderService.fulfillOrder(orgId, actorUserId, order.id(), null))
                .isInstanceOf(InvalidOrderStateException.class);

        assertThatThrownBy(() -> orderService.cancelOrder(orgId, actorUserId, order.id(), null))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    @DisplayName("Terminal order protection: FULFILLED order cannot be confirmed, cancelled, or fulfilled again")
    void fulfilledOrderIsTerminal() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Stock"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-TERMINAL-FULFILL", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 10L, new BigDecimal("10.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, orderReq, null);
        reservationService.createReservation(orgId, actorUserId, order.id(), null);
        orderService.confirmOrder(orgId, actorUserId, order.id(), null);
        orderService.fulfillOrder(orgId, actorUserId, order.id(), null);

        assertThatThrownBy(() -> orderService.confirmOrder(orgId, actorUserId, order.id(), null))
                .isInstanceOf(InvalidOrderStateException.class);

        assertThatThrownBy(() -> orderService.cancelOrder(orgId, actorUserId, order.id(), null))
                .isInstanceOf(InvalidOrderStateException.class);

        assertThatThrownBy(() -> orderService.fulfillOrder(orgId, actorUserId, order.id(), null))
                .isInstanceOf(InvalidOrderStateException.class);
    }

    @Test
    @DisplayName("Split line fulfillment: Order allocating across multiple warehouses fulfills cleanly and reconciles")
    void splitLineFulfillmentAcrossWarehouses() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 15L, "WH1 Stock"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse2.getId(), 25L, "WH2 Stock"), null);

        // Line requests 30 units: splits 15 from WH1 (priority 10) and 15 from WH2 (priority 20)
        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-SPLIT-FULFILL", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 30L, new BigDecimal("10.0000")))
        );
        OrderResponse order = orderService.createOrder(orgId, actorUserId, orderReq, null);
        reservationService.createReservation(orgId, actorUserId, order.id(), null);
        orderService.confirmOrder(orgId, actorUserId, order.id(), null);

        // Fulfill
        OrderResponse fulfilled = orderService.fulfillOrder(orgId, actorUserId, order.id(), null);
        assertThat(fulfilled.status()).isEqualTo(OrderStatus.FULFILLED);

        // WH1 balance: on_hand = 0, reserved = 0
        InventoryBalance bal1 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(bal1.getOnHand()).isEqualTo(0L);
        assertThat(bal1.getReserved()).isEqualTo(0L);

        // WH2 balance: on_hand = 10 (25 - 15), reserved = 0
        InventoryBalance bal2 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse2.getId()).orElseThrow();
        assertThat(bal2.getOnHand()).isEqualTo(10L);
        assertThat(bal2.getReserved()).isEqualTo(0L);

        // Reconcile
        ReconciliationReport report = reconciliationService.reconcile(orgId);
        assertThat(report.discrepantBalances()).isEqualTo(0);
        assertThat(report.discrepancies()).isEmpty();
    }

    @Test
    @DisplayName("Integrity: Inventory reconciliation remains 100% clean at every step of order lifecycle")
    void reconciliationIntegrityThroughoutLifecycle() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 200L, "Opening"), null);

        // Check 1: Opening balance
        ReconciliationReport r1 = reconciliationService.reconcile(orgId);
        assertThat(r1.discrepantBalances()).isEqualTo(0);

        // Order 1: Reserved -> Confirmed -> Fulfilled
        OrderResponse o1 = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("C-INT-1", null, "USD", List.of(new OrderItemRequest(productA.getId(), 50L, new BigDecimal("10.0000")))), null);
        reservationService.createReservation(orgId, actorUserId, o1.id(), null);

        // Check 2: Reserved
        ReconciliationReport r2 = reconciliationService.reconcile(orgId);
        assertThat(r2.discrepantBalances()).isEqualTo(0);

        orderService.confirmOrder(orgId, actorUserId, o1.id(), null);

        // Check 3: Confirmed
        ReconciliationReport r3 = reconciliationService.reconcile(orgId);
        assertThat(r3.discrepantBalances()).isEqualTo(0);

        orderService.fulfillOrder(orgId, actorUserId, o1.id(), null);

        // Check 4: Fulfilled (on_hand = 150, reserved = 0)
        ReconciliationReport r4 = reconciliationService.reconcile(orgId);
        assertThat(r4.discrepantBalances()).isEqualTo(0);

        // Order 2: Reserved -> Cancelled
        OrderResponse o2 = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("C-INT-2", null, "USD", List.of(new OrderItemRequest(productA.getId(), 30L, new BigDecimal("10.0000")))), null);
        reservationService.createReservation(orgId, actorUserId, o2.id(), null);
        orderService.cancelOrder(orgId, actorUserId, o2.id(), null);

        // Check 5: Cancelled (on_hand = 150, reserved = 0)
        ReconciliationReport r5 = reconciliationService.reconcile(orgId);
        assertThat(r5.discrepantBalances()).isEqualTo(0);
        assertThat(r5.discrepancies()).isEmpty();
    }
}
