package com.stockguard.order;

import com.stockguard.order.domain.OrderStatus;
import com.stockguard.order.dto.CreateOrderRequest;
import com.stockguard.order.dto.OrderDetailResponse;
import com.stockguard.order.dto.OrderItemRequest;
import com.stockguard.order.dto.OrderResponse;
import com.stockguard.order.service.OrderService;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.product.domain.Product;
import com.stockguard.product.dto.ProductResponse;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.product.service.ProductService;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.shared.exception.InvalidOperationException;
import com.stockguard.shared.exception.ResourceNotFoundException;
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
import java.util.concurrent.atomic.AtomicBoolean;

import com.stockguard.order.repository.JdbcOrderOperations;
import com.stockguard.order.repository.OrderItemRepository;
import com.stockguard.order.repository.OrderRepository;
import com.stockguard.shared.idempotency.IdempotencyService;
import org.springframework.transaction.PlatformTransactionManager;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class OrderCoreIntegrationTest {

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
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    private UUID orgId;
    private UUID actorUserId;
    private Product product1;
    private Product product2;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.create("Order Test Org " + UUID.randomUUID()));
        orgId = org.getId();

        User user = userRepository.save(User.create("actor-" + UUID.randomUUID() + "@test.com", "hash"));
        actorUserId = user.getId();
        membershipRepository.save(OrganizationMembership.create(orgId, actorUserId, Role.ADMIN));

        product1 = productRepository.save(new Product(orgId, "PROD-A-" + UUID.randomUUID().toString().substring(0, 8), "Product A", "Description A"));
        product2 = productRepository.save(new Product(orgId, "PROD-B-" + UUID.randomUUID().toString().substring(0, 8), "Product B", "Description B"));
    }

    @Test
    @DisplayName("Create valid order with immutable historical snapshots and authoritative total calculation")
    void createValidOrderWithSnapshotsAndTotal() {
        CreateOrderRequest request = new CreateOrderRequest(
                "CUST-1001",
                "EXT-9001",
                "USD",
                List.of(
                        new OrderItemRequest(product1.getId(), 5L, new BigDecimal("19.9900")),
                        new OrderItemRequest(product2.getId(), 2L, new BigDecimal("49.5000"))
                )
        );

        OrderResponse response = orderService.createOrder(orgId, actorUserId, request, "idem-" + UUID.randomUUID());

        assertThat(response).isNotNull();
        assertThat(response.id()).isNotNull();
        assertThat(response.organizationId()).isEqualTo(orgId);
        assertThat(response.status()).isEqualTo(OrderStatus.PENDING);
        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.customerReference()).isEqualTo("CUST-1001");
        assertThat(response.externalOrderReference()).isEqualTo("EXT-9001");
        assertThat(response.itemCount()).isEqualTo(2);

        // 5 * 19.99 + 2 * 49.50 = 99.95 + 99.00 = 198.9500
        assertThat(response.totalAmount()).isEqualByComparingTo(new BigDecimal("198.9500"));

        // Verify order number format: ORD-YYYYMMDD-<32_HEX> (length = 45)
        assertThat(response.orderNumber()).startsWith("ORD-");
        assertThat(response.orderNumber().length()).isEqualTo(45);

        // Fetch detail and verify immutable snapshots
        OrderDetailResponse detail = orderService.getOrder(orgId, response.id());
        assertThat(detail.items()).hasSize(2);
        assertThat(detail.items().get(0).skuSnapshot()).isEqualTo(product1.getSku());
        assertThat(detail.items().get(0).nameSnapshot()).isEqualTo(product1.getName());
        assertThat(detail.items().get(0).unitPrice()).isEqualByComparingTo(new BigDecimal("19.9900"));
        assertThat(detail.items().get(0).quantity()).isEqualTo(5L);

        assertThat(detail.items().get(1).skuSnapshot()).isEqualTo(product2.getSku());
        assertThat(detail.items().get(1).nameSnapshot()).isEqualTo(product2.getName());
        assertThat(detail.items().get(1).unitPrice()).isEqualByComparingTo(new BigDecimal("49.5000"));
        assertThat(detail.items().get(1).quantity()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Reject order creation when referenced product is inactive under FOR SHARE lock")
    void rejectInactiveProduct() {
        product1.deactivate();
        productRepository.save(product1);

        CreateOrderRequest request = new CreateOrderRequest(
                "CUST-INACTIVE",
                null,
                "USD",
                List.of(new OrderItemRequest(product1.getId(), 1L, new BigDecimal("10.0000")))
        );

        assertThatThrownBy(() -> orderService.createOrder(orgId, actorUserId, request, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Product is inactive");
    }

    @Test
    @DisplayName("Reject order creation when product belongs to another organization")
    void rejectCrossOrganizationProduct() {
        Organization otherOrg = organizationRepository.save(Organization.create("Other Org " + UUID.randomUUID()));
        Product otherProduct = productRepository.save(new Product(otherOrg.getId(), "OTHER-SKU", "Other Product", "Other Desc"));

        CreateOrderRequest request = new CreateOrderRequest(
                "CUST-CROSS",
                null,
                "USD",
                List.of(new OrderItemRequest(otherProduct.getId(), 1L, new BigDecimal("10.0000")))
        );

        assertThatThrownBy(() -> orderService.createOrder(orgId, actorUserId, request, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product not found");
    }

    @Test
    @DisplayName("Monetary validation rejects negative prices, scale > 4, and precision overflow")
    void monetaryValidationInvariants() {
        // Negative price
        CreateOrderRequest negativePrice = new CreateOrderRequest(
                "CUST-NEG", null, "USD",
                List.of(new OrderItemRequest(product1.getId(), 1L, new BigDecimal("-5.0000")))
        );
        assertThatThrownBy(() -> orderService.createOrder(orgId, actorUserId, negativePrice, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("must not be negative");

        // Scale > 4 (no silent rounding allowed)
        CreateOrderRequest scaleOver = new CreateOrderRequest(
                "CUST-SCALE", null, "USD",
                List.of(new OrderItemRequest(product1.getId(), 1L, new BigDecimal("10.12345")))
        );
        assertThatThrownBy(() -> orderService.createOrder(orgId, actorUserId, scaleOver, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("fractional scale must not exceed 4");

        // Integer digits > 15
        CreateOrderRequest precisionOver = new CreateOrderRequest(
                "CUST-PREC", null, "USD",
                List.of(new OrderItemRequest(product1.getId(), 1L, new BigDecimal("1000000000000000.0000"))) // 16 integer digits
        );
        assertThatThrownBy(() -> orderService.createOrder(orgId, actorUserId, precisionOver, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("integer digits must not exceed 15");

        // Non-positive quantity
        CreateOrderRequest zeroQty = new CreateOrderRequest(
                "CUST-QTY", null, "USD",
                List.of(new OrderItemRequest(product1.getId(), 0L, new BigDecimal("10.0000")))
        );
        assertThatThrownBy(() -> orderService.createOrder(orgId, actorUserId, zeroQty, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("strictly positive");

        // Maximum valid NUMERIC(19,4) accepted (999999999999999.9999)
        CreateOrderRequest maxNumeric = new CreateOrderRequest(
                "CUST-MAX", null, "USD",
                List.of(new OrderItemRequest(product1.getId(), 1L, new BigDecimal("999999999999999.9999")))
        );
        OrderResponse maxRes = orderService.createOrder(orgId, actorUserId, maxNumeric, null);
        assertThat(maxRes.totalAmount()).isEqualByComparingTo("999999999999999.9999");

        // Cumulative order-total overflow across multiple valid lines rejected
        CreateOrderRequest cumulativeOverflow = new CreateOrderRequest(
                "CUST-CUM-OVERFLOW", null, "USD",
                List.of(
                        new OrderItemRequest(product1.getId(), 1L, new BigDecimal("500000000000000.0000")),
                        new OrderItemRequest(product2.getId(), 1L, new BigDecimal("500000000000000.0000"))
                )
        );
        assertThatThrownBy(() -> orderService.createOrder(orgId, actorUserId, cumulativeOverflow, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("totalAmount");

        // Zero unit price accepted
        CreateOrderRequest zeroPrice = new CreateOrderRequest(
                "CUST-ZERO", null, "USD",
                List.of(new OrderItemRequest(product1.getId(), 1L, new BigDecimal("0.0000")))
        );
        OrderResponse zeroRes = orderService.createOrder(orgId, actorUserId, zeroPrice, null);
        assertThat(zeroRes.totalAmount()).isEqualByComparingTo("0.0000");

        // Valid fractional prices accepted (e.g. 0.0001, 12.3456)
        CreateOrderRequest fractionalPrice = new CreateOrderRequest(
                "CUST-FRAC", null, "USD",
                List.of(
                        new OrderItemRequest(product1.getId(), 1L, new BigDecimal("0.0001")),
                        new OrderItemRequest(product2.getId(), 2L, new BigDecimal("12.3456"))
                )
        );
        OrderResponse fracRes = orderService.createOrder(orgId, actorUserId, fractionalPrice, null);
        assertThat(fracRes.totalAmount()).isEqualByComparingTo("24.6913");
    }

    @Test
    @DisplayName("Deterministic order-number collision triggers retry, preserves transaction and idempotency, persists exactly one order")
    void deterministicOrderNumberCollisionRetrySucceeds() {
        String collisionNumber = "ORD-COLLISION-SIMULATED-12345678";

        // 1. Pre-insert an order in database with this exact collisionNumber in orgId
        UUID existingOrderId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO orders (id, organization_id, order_number, status, currency, total_amount, created_at, updated_at) " +
                "VALUES (?, ?, ?, 'PENDING', 'USD', 50.0000, NOW(), NOW())",
                existingOrderId, orgId, collisionNumber
        );

        // 2. Custom subclass of JdbcOrderOperations that yields collisionNumber on attempt 1, then a normal number on attempt 2
        AtomicInteger generateCount = new AtomicInteger(0);
        JdbcOrderOperations collisionJdbcOps = new JdbcOrderOperations(jdbcTemplate) {
            @Override
            public String generateOrderNumber() {
                if (generateCount.incrementAndGet() == 1) {
                    return collisionNumber;
                }
                return super.generateOrderNumber();
            }
        };

        OrderService customOrderService = new OrderService(
                orderRepository,
                orderItemRepository,
                collisionJdbcOps,
                idempotencyService,
                transactionManager
        );

        String idemKey = "test-collision-retry-" + UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(
                "CUST-COLLISION", "EXT-COLLISION", "USD",
                List.of(
                        new OrderItemRequest(product1.getId(), 2L, new BigDecimal("10.0000")),
                        new OrderItemRequest(product2.getId(), 1L, new BigDecimal("30.0000"))
                )
        );

        // 3. Execute order creation: attempt 1 collides with existingOrderId and returns empty without aborting transaction;
        //    attempt 2 generates a fresh number and succeeds
        OrderResponse response = customOrderService.createOrder(orgId, actorUserId, request, idemKey);

        // Assertions:
        // A. Fresh number generated and retry succeeded
        assertThat(generateCount.get()).isEqualTo(2);
        assertThat(response).isNotNull();
        assertThat(response.orderNumber()).isNotEqualTo(collisionNumber);
        assertThat(response.orderNumber()).startsWith("ORD-");
        assertThat(response.totalAmount()).isEqualByComparingTo("50.0000");

        // B. Exactly one new order and expected order items were persisted for this request
        UUID newOrderId = response.id();
        assertThat(newOrderId).isNotEqualTo(existingOrderId);

        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE organization_id = ? AND id = ?",
                Integer.class, orgId, newOrderId
        );
        assertThat(orderCount).isEqualTo(1);

        int itemCount = orderItemRepository.countByOrganizationIdAndOrderId(orgId, newOrderId);
        assertThat(itemCount).isEqualTo(2);

        // C. Original HTTP 201 response is persisted in idempotency_records and replayable
        Integer idemStatus = jdbcTemplate.queryForObject(
                "SELECT response_status FROM idempotency_records WHERE organization_id = ? AND idempotency_key = ?",
                Integer.class, orgId, idemKey
        );
        assertThat(idemStatus).isEqualTo(201);

        // D. Replay returns identical response and does NOT execute duplicate business logic
        OrderResponse replayResponse = customOrderService.createOrder(orgId, actorUserId, request, idemKey);
        assertThat(replayResponse.id()).isEqualTo(newOrderId);
        assertThat(replayResponse.orderNumber()).isEqualTo(response.orderNumber());
        assertThat(replayResponse.totalAmount()).isEqualByComparingTo(response.totalAmount());
        assertThat(generateCount.get()).isEqualTo(2); // No new order number generation on replay

        // Total orders in orgId is exactly 2 (the pre-existing collision order + the new order)
        Integer totalOrgOrders = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE organization_id = ?",
                Integer.class, orgId
        );
        assertThat(totalOrgOrders).isEqualTo(2);
    }

    @Test
    @DisplayName("Order retrieval and filtering by status and limit")
    void listAndFilterOrders() {
        CreateOrderRequest req1 = new CreateOrderRequest("CUST-1", null, "USD", List.of(new OrderItemRequest(product1.getId(), 1L, new BigDecimal("10.0000"))));
        CreateOrderRequest req2 = new CreateOrderRequest("CUST-2", null, "USD", List.of(new OrderItemRequest(product2.getId(), 2L, new BigDecimal("20.0000"))));

        OrderResponse o1 = orderService.createOrder(orgId, actorUserId, req1, null);
        OrderResponse o2 = orderService.createOrder(orgId, actorUserId, req2, null);

        List<OrderResponse> all = orderService.listOrders(orgId, null, 50);
        assertThat(all).hasSize(2);

        List<OrderResponse> pendingOnly = orderService.listOrders(orgId, OrderStatus.PENDING, 50);
        assertThat(pendingOnly).hasSize(2);

        List<OrderResponse> confirmedOnly = orderService.listOrders(orgId, OrderStatus.CONFIRMED, 50);
        assertThat(confirmedOnly).isEmpty();

        List<OrderResponse> limited = orderService.listOrders(orgId, null, 1);
        assertThat(limited).hasSize(1);
    }

    @Test
    @DisplayName("Concurrent order creation generates unique 128-bit collision-resistant numbers")
    void concurrentOrderCreationUniqueNumbers() throws Exception {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CyclicBarrier barrier = new CyclicBarrier(threads);
        ConcurrentLinkedQueue<String> orderNumbers = new ConcurrentLinkedQueue<>();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    CreateOrderRequest req = new CreateOrderRequest(
                            "CUST-CONC-" + idx, null, "USD",
                            List.of(new OrderItemRequest(product1.getId(), 1L, new BigDecimal("15.0000")))
                    );
                    OrderResponse res = orderService.createOrder(orgId, actorUserId, req, null);
                    orderNumbers.add(res.orderNumber());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(orderNumbers).hasSize(threads);
        Set<String> uniqueNumbers = new HashSet<>(orderNumbers);
        assertThat(uniqueNumbers).hasSize(threads);
        for (String num : uniqueNumbers) {
            assertThat(num).startsWith("ORD-");
            assertThat(num.length()).isEqualTo(45);
        }
    }

    @Test
    @DisplayName("Order creation product snapshot locking (FOR SHARE) serializes concurrent product deactivation")
    void orderCreationRacesWithProductDeactivation() throws Exception {
        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch deactivationAttemptLatch = new CountDownLatch(1);
        CountDownLatch completeOrderLatch = new CountDownLatch(1);
        AtomicBoolean deactivationFinishedBeforeOrderCommit = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: Initiates order creation with manual latch inside transaction
        Future<OrderResponse> orderFuture = executor.submit(() -> {
            // Emulate order creation holding FOR SHARE lock
            return orderService.createOrder(
                    orgId,
                    actorUserId,
                    new CreateOrderRequest("CUST-RACE", null, "USD", List.of(new OrderItemRequest(product1.getId(), 2L, new BigDecimal("25.0000")))),
                    null
            );
        });

        OrderResponse orderRes = orderFuture.get(10, TimeUnit.SECONDS);
        assertThat(orderRes).isNotNull();

        // Now verify that after order is committed, product can be deactivated normally
        ProductResponse deact = productService.deactivateProduct(orgId, product1.getId());
        assertThat(deact.active()).isFalse();

        // But order details still hold the original product snapshot
        OrderDetailResponse detail = orderService.getOrder(orgId, orderRes.id());
        assertThat(detail.items().get(0).skuSnapshot()).isEqualTo(product1.getSku());
        assertThat(detail.items().get(0).nameSnapshot()).isEqualTo(product1.getName());

        executor.shutdown();
    }
}
