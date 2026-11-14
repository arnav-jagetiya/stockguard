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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class ReservationExpirationConcurrencyIntegrationTest {

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
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID orgId;
    private UUID actorUserId;
    private Warehouse warehouse;
    private Product product;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.create("Expiration Concurrency Org " + UUID.randomUUID()));
        orgId = org.getId();

        User actor = userRepository.save(User.create("exp.conc." + UUID.randomUUID() + "@stockguard.com", "hash"));
        actorUserId = actor.getId();
        membershipRepository.save(OrganizationMembership.create(orgId, actorUserId, Role.ADMIN));

        warehouse = warehouseRepository.save(new Warehouse(orgId, "WH-CONC-" + UUID.randomUUID().toString().substring(0, 5), "WH Conc", "Loc", 10));
        product = productRepository.save(new Product(orgId, "PROD-CONC-" + UUID.randomUUID().toString().substring(0, 5), "Product Conc", null));
    }

    @Test
    @DisplayName("D20: Multiple concurrent workers process shared expired candidate set with zero duplicate releases")
    void multiWorkerConcurrentSweep() throws Exception {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product.getId(), warehouse.getId(), 1000L, "Seed stock"), null);

        int candidateCount = 10;
        for (int i = 0; i < candidateCount; i++) {
            OrderResponse o = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("CUST-WORKER-" + i, null, "USD", List.of(new OrderItemRequest(product.getId(), 5L, new BigDecimal("10.0000")))), null);
            ReservationResponse r = reservationService.createReservation(orgId, actorUserId, o.id(), null);
            jdbcTemplate.update("UPDATE reservations SET expires_at = NOW() - INTERVAL '" + (10 + i) + " minutes' WHERE id = ?", r.id());
        }

        // Run 2 worker threads concurrently
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);

        Future<ExpirationBatchResult> f1 = executor.submit(() -> {
            startLatch.await();
            return expirationService.sweepExpiredReservations(orgId);
        });

        Future<ExpirationBatchResult> f2 = executor.submit(() -> {
            startLatch.await();
            return expirationService.sweepExpiredReservations(orgId);
        });

        startLatch.countDown();
        ExpirationBatchResult res1 = f1.get(15, TimeUnit.SECONDS);
        ExpirationBatchResult res2 = f2.get(15, TimeUnit.SECONDS);
        executor.shutdown();

        // Combined successes equal total candidate count
        int totalExpired = res1.successCount() + res2.successCount();
        assertThat(totalExpired).isEqualTo(candidateCount);

        // All 10 orders transitioned to EXPIRED
        Integer expiredOrders = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE organization_id = ? AND status = 'EXPIRED'",
                Integer.class, orgId
        );
        assertThat(expiredOrders).isEqualTo(candidateCount);

        // All 10 reservations transitioned to EXPIRED
        Integer expiredReservations = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservations WHERE organization_id = ? AND status = 'EXPIRED'",
                Integer.class, orgId
        );
        assertThat(expiredReservations).isEqualTo(candidateCount);

        // Inventory reserved balance is exactly 0 (released 5 * 10 = 50), on_hand is still 1000
        InventoryBalance bal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product.getId(), warehouse.getId()).orElseThrow();
        assertThat(bal.getOnHand()).isEqualTo(1000L);
        assertThat(bal.getReserved()).isEqualTo(0L);

        // Exactly 10 RELEASE transactions created
        Integer releaseTxCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_transactions WHERE organization_id = ? AND transaction_type = 'RELEASE'",
                Integer.class, orgId
        );
        assertThat(releaseTxCount).isEqualTo(candidateCount);

        // Reconciliation is 100% clean
        ReconciliationReport report = reconciliationService.reconcile(orgId);
        assertThat(report.discrepantBalances()).isEqualTo(0);
        assertThat(report.discrepancies()).isEmpty();
    }

    @Test
    @DisplayName("D21: SKIP LOCKED allows workers to claim different candidates without waiting for another locked order")
    void skipLockedParallelClaimingWithoutBlocking() throws Exception {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product.getId(), warehouse.getId(), 500L, "Seed stock"), null);

        // Candidate 1 (older expiry)
        OrderResponse o1 = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("CUST-SKIP-1", null, "USD", List.of(new OrderItemRequest(product.getId(), 10L, new BigDecimal("10.0000")))), null);
        ReservationResponse r1 = reservationService.createReservation(orgId, actorUserId, o1.id(), null);
        jdbcTemplate.update("UPDATE reservations SET expires_at = NOW() - INTERVAL '10 minutes' WHERE id = ?", r1.id());

        // Candidate 2 (newer expiry)
        OrderResponse o2 = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("CUST-SKIP-2", null, "USD", List.of(new OrderItemRequest(product.getId(), 15L, new BigDecimal("10.0000")))), null);
        ReservationResponse r2 = reservationService.createReservation(orgId, actorUserId, o2.id(), null);
        jdbcTemplate.update("UPDATE reservations SET expires_at = NOW() - INTERVAL '5 minutes' WHERE id = ?", r2.id());

        CountDownLatch o1LockedLatch = new CountDownLatch(1);
        CountDownLatch o2ProcessedLatch = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        // Thread 1: Locks Candidate 1 FOR UPDATE
        Future<?> f1 = executor.submit(() -> {
            TransactionTemplate tt = new TransactionTemplate(transactionManager);
            tt.execute(status -> {
                jdbcTemplate.queryForObject("SELECT id FROM orders WHERE id = ? FOR UPDATE", UUID.class, o1.id());
                o1LockedLatch.countDown();
                try {
                    o2ProcessedLatch.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {}
                return null;
            });
        });

        // Wait until Candidate 1 is locked by Thread 1
        assertThat(o1LockedLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread 2: Runs worker processNextCandidate. It MUST skip Candidate 1 and claim Candidate 2 immediately!
        Future<ReservationExpirationService.CandidateExecutionResult> f2 = executor.submit(() -> {
            return expirationService.processNextCandidateInTransaction(orgId, Collections.emptySet());
        });

        // Thread 2 completes promptly without blocking
        ReservationExpirationService.CandidateExecutionResult res2 = f2.get(3, TimeUnit.SECONDS);
        o2ProcessedLatch.countDown();
        f1.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        // Verify Thread 2 skipped o1 and successfully claimed o2!
        assertThat(res2.type()).isEqualTo(ReservationExpirationService.CandidateResultType.SUCCESS);
        assertThat(res2.orderId()).isEqualTo(o2.id());

        // o2 is EXPIRED
        var dbOrder2 = orderRepository.findByIdAndOrganizationId(o2.id(), orgId).orElseThrow();
        assertThat(dbOrder2.getStatus()).isEqualTo(OrderStatus.EXPIRED);

        // o1 remained untouched while locked
        var dbOrder1 = orderRepository.findByIdAndOrganizationId(o1.id(), orgId).orElseThrow();
        assertThat(dbOrder1.getStatus()).isEqualTo(OrderStatus.PENDING);
    }

    @Test
    @DisplayName("D22-D23: Genuinely concurrent confirmation racing with expiration produces exactly one winner and zero duplicate releases")
    void concurrentConfirmationRacingWithExpirationHasExactlyOneWinner() throws Exception {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product.getId(), warehouse.getId(), 500L, "Seed stock"), null);

        int rounds = 6;
        for (int i = 0; i < rounds; i++) {
            OrderResponse order = orderService.createOrder(orgId, actorUserId, new CreateOrderRequest("CUST-RACE-" + i, null, "USD", List.of(new OrderItemRequest(product.getId(), 10L, new BigDecimal("10.0000")))), null);
            ReservationResponse res = reservationService.createReservation(orgId, actorUserId, order.id(), null);

            // Mark expired so expiration worker is eligible
            jdbcTemplate.update("UPDATE reservations SET expires_at = NOW() - INTERVAL '1 second' WHERE id = ?", res.id());

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch startGate = new CountDownLatch(1);

            AtomicBoolean confirmationSucceeded = new AtomicBoolean(false);
            AtomicBoolean confirmationThrewInvalidState = new AtomicBoolean(false);
            AtomicBoolean expirationSucceeded = new AtomicBoolean(false);
            AtomicBoolean expirationSkipped = new AtomicBoolean(false);

            Future<?> fConfirm = executor.submit(() -> {
                try {
                    startGate.await();
                    orderService.confirmOrder(orgId, actorUserId, order.id(), null);
                    confirmationSucceeded.set(true);
                } catch (InvalidOrderStateException e) {
                    confirmationThrewInvalidState.set(true);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Future<?> fExpire = executor.submit(() -> {
                try {
                    startGate.await();
                    boolean ok = expirationService.expireSpecificCandidate(orgId, order.id());
                    if (ok) {
                        expirationSucceeded.set(true);
                    } else {
                        expirationSkipped.set(true);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            startGate.countDown();
            fConfirm.get(10, TimeUnit.SECONDS);
            fExpire.get(10, TimeUnit.SECONDS);
            executor.shutdown();

            var finalOrder = orderRepository.findByIdAndOrganizationId(order.id(), orgId).orElseThrow();
            Reservation finalRes = reservationRepository.findByIdAndOrganizationId(res.id(), orgId).orElseThrow();

            // Verify mutual exclusivity: exactly one operation won
            if (confirmationSucceeded.get()) {
                // Confirmation won
                assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
                assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.ACTIVE);
                assertThat(finalRes.getExpiresAt()).isNull();
                assertThat(expirationSkipped.get()).isTrue();
                assertThat(expirationSucceeded.get()).isFalse();
            } else {
                // Expiration won
                assertThat(confirmationThrewInvalidState.get()).isTrue();
                assertThat(finalOrder.getStatus()).isEqualTo(OrderStatus.EXPIRED);
                assertThat(finalRes.getStatus()).isEqualTo(ReservationStatus.EXPIRED);
                assertThat(expirationSucceeded.get()).isTrue();
            }
        }

        // Entire inventory reconciles with 0 discrepancies
        ReconciliationReport report = reconciliationService.reconcile(orgId);
        assertThat(report.discrepantBalances()).isEqualTo(0);
        assertThat(report.discrepancies()).isEmpty();
    }
}
