package com.stockguard.reservation;

import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.dto.OpeningBalanceRequest;
import com.stockguard.inventory.repository.InventoryBalanceRepository;
import com.stockguard.inventory.service.InventoryReconciliationService;
import com.stockguard.inventory.service.InventoryService;
import com.stockguard.order.domain.Order;
import com.stockguard.order.domain.OrderItem;
import com.stockguard.order.domain.OrderStatus;
import com.stockguard.order.dto.CreateOrderRequest;
import com.stockguard.order.dto.OrderItemRequest;
import com.stockguard.order.dto.OrderResponse;
import com.stockguard.order.exception.InvalidOrderStateException;
import com.stockguard.order.repository.OrderItemRepository;
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
import com.stockguard.reservation.domain.ReservationAllocation;
import com.stockguard.reservation.domain.ReservationStatus;
import com.stockguard.reservation.dto.ReservationAllocationResponse;
import com.stockguard.reservation.dto.ReservationResponse;
import com.stockguard.reservation.repository.ReservationAllocationRepository;
import com.stockguard.reservation.repository.ReservationRepository;
import com.stockguard.reservation.service.ReservationService;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.shared.exception.InsufficientStockException;
import com.stockguard.shared.exception.InvalidOperationException;
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
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class ReservationCoreIntegrationTest {

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
    private ReservationService reservationService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryReconciliationService reconciliationService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private InventoryBalanceRepository balanceRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationAllocationRepository allocationRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID orgId;
    private UUID actorUserId;
    private Warehouse warehouse1;
    private Warehouse warehouse2;
    private Warehouse warehouse3;
    private Product productA;
    private Product productB;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.create("Res Core Org " + UUID.randomUUID()));
        orgId = org.getId();

        User user = userRepository.save(User.create("res.actor." + UUID.randomUUID() + "@stockguard.com", "hash"));
        actorUserId = user.getId();
        membershipRepository.save(OrganizationMembership.create(orgId, actorUserId, Role.ADMIN));

        // Warehouse 1: priority 10, code WH-10
        warehouse1 = warehouseRepository.save(new Warehouse(orgId, "WH-10-" + UUID.randomUUID().toString().substring(0, 5), "Warehouse 10", "Loc 1", 10));
        // Warehouse 2: priority 20, code WH-20
        warehouse2 = warehouseRepository.save(new Warehouse(orgId, "WH-20-" + UUID.randomUUID().toString().substring(0, 5), "Warehouse 20", "Loc 2", 20));
        // Warehouse 3: priority 30, code WH-30
        warehouse3 = warehouseRepository.save(new Warehouse(orgId, "WH-30-" + UUID.randomUUID().toString().substring(0, 5), "Warehouse 30", "Loc 3", 30));

        productA = productRepository.save(new Product(orgId, "SKU-A-" + UUID.randomUUID().toString().substring(0, 6), "Product A", null));
        productB = productRepository.save(new Product(orgId, "SKU-B-" + UUID.randomUUID().toString().substring(0, 6), "Product B", null));
    }

    @Test
    @DisplayName("Single warehouse fulfill complete line in priority order")
    void singleWarehouseCanFulfillCompleteLine() {
        // WH1 has 100 units, WH2 has 50 units
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Initial stock"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse2.getId(), 50L, "Initial stock"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-RES-1", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 40L, new BigDecimal("20.0000")))
        );
        OrderResponse orderRes = orderService.createOrder(orgId, actorUserId, orderReq, null);

        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, orderRes.id(), null);

        assertThat(res).isNotNull();
        assertThat(res.status()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(res.allocations()).hasSize(1);

        ReservationAllocationResponse alloc = res.allocations().get(0);
        assertThat(alloc.warehouseId()).isEqualTo(warehouse1.getId()); // Highest priority WH1 fulfills
        assertThat(alloc.quantity()).isEqualTo(40L);

        // Verify balance updates: WH1 reserved = 40, on_hand = 100
        InventoryBalance b1 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(b1.getOnHand()).isEqualTo(100L);
        assertThat(b1.getReserved()).isEqualTo(40L);

        // WH2 untouched
        InventoryBalance b2 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse2.getId()).orElseThrow();
        assertThat(b2.getOnHand()).isEqualTo(50L);
        assertThat(b2.getReserved()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Single warehouse fulfillment is preferred over splitting across priority warehouses")
    void singleWarehouseFulfillmentPreferredOverSplitting() {
        // WH1 (priority 10) has 10 units (cannot fulfill 25)
        // WH2 (priority 20) has 50 units (can fulfill 25 completely)
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 10L, "Stock WH1"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse2.getId(), 50L, "Stock WH2"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-SINGLE-PREF", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 25L, new BigDecimal("15.0000")))
        );
        OrderResponse orderRes = orderService.createOrder(orgId, actorUserId, orderReq, null);

        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, orderRes.id(), null);

        // WH2 should fulfill entire line (25 units) rather than splitting 10 from WH1 and 15 from WH2
        assertThat(res.allocations()).hasSize(1);
        assertThat(res.allocations().get(0).warehouseId()).isEqualTo(warehouse2.getId());
        assertThat(res.allocations().get(0).quantity()).isEqualTo(25L);

        InventoryBalance b1 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(b1.getReserved()).isEqualTo(0L);

        InventoryBalance b2 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse2.getId()).orElseThrow();
        assertThat(b2.getReserved()).isEqualTo(25L);
    }

    @Test
    @DisplayName("Split allocation across warehouses in priority order when no single warehouse suffices")
    void splitAllocationAcrossWarehousesWorks() {
        // WH1 (prio 10) has 15 units
        // WH2 (prio 20) has 20 units
        // Request needs 30 units: neither WH1 nor WH2 alone can fulfill 30
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 15L, "Stock WH1"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse2.getId(), 20L, "Stock WH2"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-SPLIT", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 30L, new BigDecimal("10.0000")))
        );
        OrderResponse orderRes = orderService.createOrder(orgId, actorUserId, orderReq, null);

        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, orderRes.id(), null);

        assertThat(res.allocations()).hasSize(2);
        // Allocation 1: 15 from WH1 (prio 10)
        // Allocation 2: 15 from WH2 (prio 20)
        ReservationAllocationResponse alloc1 = res.allocations().stream()
                .filter(a -> a.warehouseId().equals(warehouse1.getId())).findFirst().orElseThrow();
        ReservationAllocationResponse alloc2 = res.allocations().stream()
                .filter(a -> a.warehouseId().equals(warehouse2.getId())).findFirst().orElseThrow();

        assertThat(alloc1.quantity()).isEqualTo(15L);
        assertThat(alloc2.quantity()).isEqualTo(15L);

        InventoryBalance b1 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(b1.getReserved()).isEqualTo(15L);

        InventoryBalance b2 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse2.getId()).orElseThrow();
        assertThat(b2.getReserved()).isEqualTo(15L);
    }

    @Test
    @DisplayName("Normalized warehouse code breaks ties deterministically when priority is equal")
    void normalizedWarehouseCodeBreaksTies() {
        // Create WH-A and WH-B with the same priority (5)
        Warehouse whA = warehouseRepository.save(new Warehouse(orgId, "WH-A-" + UUID.randomUUID().toString().substring(0, 4), "WH A", "Loc", 5));
        Warehouse whB = warehouseRepository.save(new Warehouse(orgId, "WH-B-" + UUID.randomUUID().toString().substring(0, 4), "WH B", "Loc", 5));

        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), whA.getId(), 50L, "Stock A"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), whB.getId(), 50L, "Stock B"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-TIE", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 20L, new BigDecimal("10.0000")))
        );
        OrderResponse orderRes = orderService.createOrder(orgId, actorUserId, orderReq, null);

        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, orderRes.id(), null);

        assertThat(res.allocations()).hasSize(1);
        // whA normalized code is lexicographically smaller than whB
        String expectedWhCode = whA.getNormalizedCode().compareTo(whB.getNormalizedCode()) < 0 ? whA.getNormalizedCode() : whB.getNormalizedCode();
        UUID expectedWhId = whA.getNormalizedCode().compareTo(whB.getNormalizedCode()) < 0 ? whA.getId() : whB.getId();

        assertThat(res.allocations().get(0).warehouseId()).isEqualTo(expectedWhId);
    }

    @Test
    @DisplayName("Inactive warehouses are excluded from allocation")
    void inactiveWarehousesExcluded() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Inactive WH Stock"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse2.getId(), 50L, "Active WH Stock"), null);

        // WH1 (priority 10) is deactivated after stock is established
        warehouse1.deactivate();
        warehouseRepository.save(warehouse1);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-INACTIVE-WH", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 20L, new BigDecimal("10.0000")))
        );
        OrderResponse orderRes = orderService.createOrder(orgId, actorUserId, orderReq, null);

        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, orderRes.id(), null);

        // Must allocate from active WH2, completely ignoring inactive WH1
        assertThat(res.allocations()).hasSize(1);
        assertThat(res.allocations().get(0).warehouseId()).isEqualTo(warehouse2.getId());
    }

    @Test
    @DisplayName("Inactive product rejects reservation with InvalidOperationException under lock")
    void inactiveProductRejectsReservation() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Stock"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-INACTIVE-PROD", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 10L, new BigDecimal("10.0000")))
        );
        OrderResponse orderRes = orderService.createOrder(orgId, actorUserId, orderReq, null);

        // Deactivate product after order creation
        productA.deactivate();
        productRepository.save(productA);

        assertThatThrownBy(() -> reservationService.createReservation(orgId, actorUserId, orderRes.id(), null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Product is inactive");
    }

    @Test
    @DisplayName("Missing balance rows count as zero and are NOT auto-created in database")
    void missingBalanceCountsAsZeroAndNotAutoCreated() {
        // Stock only exists in WH1; WH2 and WH3 have NO balance rows for productA
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Stock WH1"), null);

        // Verify WH2 and WH3 have no balance row
        assertThat(balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse2.getId())).isEmpty();
        assertThat(balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse3.getId())).isEmpty();

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-MISSING-BAL", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 20L, new BigDecimal("10.0000")))
        );
        OrderResponse orderRes = orderService.createOrder(orgId, actorUserId, orderReq, null);

        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, orderRes.id(), null);
        assertThat(res.allocations()).hasSize(1);
        assertThat(res.allocations().get(0).warehouseId()).isEqualTo(warehouse1.getId());

        // Verify WH2 and WH3 STILL have NO balance rows created!
        assertThat(balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse2.getId())).isEmpty();
        assertThat(balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse3.getId())).isEmpty();
    }

    @Test
    @DisplayName("Duplicate-product lines consume from shared mutable availability map without double allocation")
    void duplicateProductLinesShareAvailabilityCorrectly() {
        // WH1 has 12, WH2 has 10
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 12L, "Stock WH1"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse2.getId(), 10L, "Stock WH2"), null);

        // Line 1: 10 units of Product A
        // Line 2: 5 units of Product A
        // Total needed: 15 units
        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-DUP", null, "USD",
                List.of(
                        new OrderItemRequest(productA.getId(), 10L, new BigDecimal("10.0000")),
                        new OrderItemRequest(productA.getId(), 5L, new BigDecimal("10.0000"))
                )
        );
        OrderResponse orderRes = orderService.createOrder(orgId, actorUserId, orderReq, null);

        ReservationResponse res = reservationService.createReservation(orgId, actorUserId, orderRes.id(), null);

        // Line 1 (10 units): WH1 has 12 >= 10 -> takes 10 from WH1 (WH1 remaining: 2).
        // Line 2 (5 units): WH1 has 2 (< 5); WH2 has 10 (>= 5) -> takes 5 from WH2!
        List<OrderItem> items = orderItemRepository.findAllByOrganizationIdAndOrderIdOrderByLineNumberAsc(orgId, orderRes.id());
        UUID line1Id = items.get(0).getId();
        UUID line2Id = items.get(1).getId();

        ReservationAllocationResponse allocLine1 = res.allocations().stream()
                .filter(a -> a.orderItemId().equals(line1Id)).findFirst().orElseThrow();
        ReservationAllocationResponse allocLine2 = res.allocations().stream()
                .filter(a -> a.orderItemId().equals(line2Id)).findFirst().orElseThrow();

        assertThat(allocLine1.warehouseId()).isEqualTo(warehouse1.getId());
        assertThat(allocLine1.quantity()).isEqualTo(10L);

        assertThat(allocLine2.warehouseId()).isEqualTo(warehouse2.getId());
        assertThat(allocLine2.quantity()).isEqualTo(5L);

        InventoryBalance b1 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(b1.getReserved()).isEqualTo(10L);

        InventoryBalance b2 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse2.getId()).orElseThrow();
        assertThat(b2.getReserved()).isEqualTo(5L);
    }

    @Test
    @DisplayName("Atomicity: Insufficient inventory rejects entire reservation and leaves zero database mutations")
    void insufficientStockRollsBackEntireReservation() {
        // Product A: 100 in stock
        // Product B: only 5 in stock
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Stock A"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productB.getId(), warehouse1.getId(), 5L, "Stock B"), null);

        int initialTransactions = jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_transactions WHERE organization_id = ?", Integer.class, orgId);
        int initialLedgers = jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ?", Integer.class, orgId);

        // Order needs 20 of A (available) and 50 of B (unavailable!)
        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-FAIL-ROLLBACK", null, "USD",
                List.of(
                        new OrderItemRequest(productA.getId(), 20L, new BigDecimal("10.0000")),
                        new OrderItemRequest(productB.getId(), 50L, new BigDecimal("10.0000"))
                )
        );
        OrderResponse orderRes = orderService.createOrder(orgId, actorUserId, orderReq, null);

        assertThatThrownBy(() -> reservationService.createReservation(orgId, actorUserId, orderRes.id(), null))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Insufficient stock");

        // Verify zero reservations exist for this order
        assertThat(reservationRepository.findByOrganizationIdAndOrderId(orgId, orderRes.id())).isEmpty();
        assertThat(allocationRepository.findAllByOrganizationIdAndOrderId(orgId, orderRes.id())).isEmpty();

        // Verify balances are completely untouched
        InventoryBalance bA = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(bA.getReserved()).isEqualTo(0L);

        InventoryBalance bB = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productB.getId(), warehouse1.getId()).orElseThrow();
        assertThat(bB.getReserved()).isEqualTo(0L);

        // Verify no ledger entries or transactions were committed
        int finalTransactions = jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_transactions WHERE organization_id = ?", Integer.class, orgId);
        int finalLedgers = jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ?", Integer.class, orgId);
        assertThat(finalTransactions).isEqualTo(initialTransactions);
        assertThat(finalLedgers).isEqualTo(initialLedgers);
    }

    @Test
    @DisplayName("Exactly one successful reservation exists per order; subsequent attempts fail with InvalidOrderStateException")
    void oneSuccessfulReservationPerOrder() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Stock"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-ONE-RES", null, "USD",
                List.of(new OrderItemRequest(productA.getId(), 10L, new BigDecimal("10.0000")))
        );
        OrderResponse orderRes = orderService.createOrder(orgId, actorUserId, orderReq, null);

        ReservationResponse res1 = reservationService.createReservation(orgId, actorUserId, orderRes.id(), null);
        assertThat(res1.status()).isEqualTo(ReservationStatus.ACTIVE);

        // Attempting to reserve again without idempotency key throws InvalidOrderStateException
        assertThatThrownBy(() -> reservationService.createReservation(orgId, actorUserId, orderRes.id(), null))
                .isInstanceOf(InvalidOrderStateException.class)
                .hasMessageContaining("Order already has a reservation");
    }

    @Test
    @DisplayName("Concurrency: Two concurrent orders compete for insufficient shared inventory; aggregate reserved <= on_hand")
    void concurrentOrdersCompeteForInsufficientInventory() throws Exception {
        // Only 50 units on hand in total
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 50L, "Stock"), null);

        // Order 1 needs 30 units
        OrderResponse o1 = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("C-1", null, "USD", List.of(new OrderItemRequest(productA.getId(), 30L, new BigDecimal("10.0000")))), null);
        // Order 2 needs 30 units
        OrderResponse o2 = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("C-2", null, "USD", List.of(new OrderItemRequest(productA.getId(), 30L, new BigDecimal("10.0000")))), null);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        futures.add(executor.submit(() -> {
            try {
                barrier.await();
                reservationService.createReservation(orgId, actorUserId, o1.id(), null);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failureCount.incrementAndGet();
            }
        }));

        futures.add(executor.submit(() -> {
            try {
                barrier.await();
                reservationService.createReservation(orgId, actorUserId, o2.id(), null);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failureCount.incrementAndGet();
            }
        }));

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // Exactly one should succeed, one must fail because 30 + 30 = 60 > 50
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);

        InventoryBalance balance = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productA.getId(), warehouse1.getId()).orElseThrow();
        assertThat(balance.getReserved()).isEqualTo(30L);
        assertThat(balance.getReserved()).isLessThanOrEqualTo(balance.getOnHand());
    }

    @Test
    @DisplayName("Integrity: Mathematical reconciliation passes cleanly after reservations")
    void mathematicalReconciliationPassesCleanly() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productA.getId(), warehouse1.getId(), 100L, "Stock A"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productB.getId(), warehouse2.getId(), 100L, "Stock B"), null);

        CreateOrderRequest orderReq = new CreateOrderRequest(
                "CUST-RECON", null, "USD",
                List.of(
                        new OrderItemRequest(productA.getId(), 25L, new BigDecimal("10.0000")),
                        new OrderItemRequest(productB.getId(), 35L, new BigDecimal("10.0000"))
                )
        );
        OrderResponse orderRes = orderService.createOrder(orgId, actorUserId, orderReq, null);

        reservationService.createReservation(orgId, actorUserId, orderRes.id(), null);

        var report = reconciliationService.reconcile(orgId);
        assertThat(report.discrepantBalances()).isEqualTo(0);
        assertThat(report.discrepancies()).isEmpty();
        assertThat(report.matchingBalances()).isEqualTo(2);
    }
}
