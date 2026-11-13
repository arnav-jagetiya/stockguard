package com.stockguard.transfer;

import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.dto.OpeningBalanceRequest;
import com.stockguard.inventory.dto.ReconciliationReport;
import com.stockguard.inventory.repository.InventoryBalanceRepository;
import com.stockguard.inventory.service.InventoryReconciliationService;
import com.stockguard.inventory.service.InventoryService;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.product.domain.Product;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.shared.exception.InsufficientStockException;
import com.stockguard.transfer.dto.CreateTransferRequest;
import com.stockguard.transfer.dto.TransferItemRequest;
import com.stockguard.transfer.service.TransferService;
import com.stockguard.warehouse.domain.Warehouse;
import com.stockguard.warehouse.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class TransferConcurrencyIntegrationTest {

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
    private TransferService transferService;

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private InventoryReconciliationService reconciliationService;

    @Autowired
    private InventoryBalanceRepository balanceRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    private UUID orgId;
    private UUID actorUserId;
    private Warehouse warehouse1;
    private Warehouse warehouse2;
    private Product product1;
    private Product product2;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.create("Concurrent Transfer Org " + UUID.randomUUID()));
        orgId = org.getId();

        User user = User.create("concurrent.transfer." + UUID.randomUUID() + "@stockguard.com", "hash");
        user = userRepository.save(user);
        actorUserId = user.getId();

        OrganizationMembership membership = OrganizationMembership.create(orgId, actorUserId, Role.OPERATOR);
        membershipRepository.save(membership);

        warehouse1 = warehouseRepository.save(new Warehouse(orgId, "W1-" + UUID.randomUUID().toString().substring(0, 6), "Warehouse One", "Loc 1", 1));
        warehouse2 = warehouseRepository.save(new Warehouse(orgId, "W2-" + UUID.randomUUID().toString().substring(0, 6), "Warehouse Two", "Loc 2", 2));

        product1 = productRepository.save(new Product(orgId, "P1-" + UUID.randomUUID().toString().substring(0, 6), "Prod One", null));
        product2 = productRepository.save(new Product(orgId, "P2-" + UUID.randomUUID().toString().substring(0, 6), "Prod Two", null));
    }

    @Test
    @DisplayName("Concurrent transfers competing for limited stock preserve exact inventory bounds without overdrawing")
    void concurrentTransfersCompeteForStock() throws Exception {
        // Initial stock = 100 at warehouse1
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product1.getId(), warehouse1.getId(), 100L, "Initial stock"), null);

        // 10 concurrent threads each attempting to transfer 20 units (total demand = 200, supply = 100)
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger insufficientCount = new AtomicInteger();
        AtomicInteger otherFailures = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    transferService.transferStock(
                            orgId,
                            actorUserId,
                            new CreateTransferRequest(
                                    warehouse1.getId(),
                                    warehouse2.getId(),
                                    List.of(new TransferItemRequest(product1.getId(), 20L)),
                                    "Concurrent transfer competition"
                            ),
                            null
                    );
                    successCount.incrementAndGet();
                } catch (InsufficientStockException ex) {
                    insufficientCount.incrementAndGet();
                } catch (Exception ex) {
                    otherFailures.incrementAndGet();
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(otherFailures.get()).isZero();
        assertThat(successCount.get()).isEqualTo(5); // Exactly 5 * 20 = 100 units transferred
        assertThat(insufficientCount.get()).isEqualTo(5); // Exactly 5 rejected

        InventoryBalance w1Bal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product1.getId(), warehouse1.getId()).orElseThrow();
        InventoryBalance w2Bal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product1.getId(), warehouse2.getId()).orElseThrow();

        assertThat(w1Bal.getOnHand()).isZero();
        assertThat(w2Bal.getOnHand()).isEqualTo(100L);

        ReconciliationReport report = reconciliationService.reconcile(orgId);
        assertThat(report.discrepantBalances()).isZero();
    }

    @Test
    @DisplayName("Opposite-direction transfers between two warehouses execute without deadlocks due to canonical lock ordering")
    void oppositeDirectionTransfersPreventDeadlock() throws Exception {
        // Establish 100 units at warehouse 1 and 100 units at warehouse 2
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product1.getId(), warehouse1.getId(), 100L, "Initial stock"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product1.getId(), warehouse2.getId(), 100L, "Initial stock"), null);

        int transfersPerDirection = 6;
        int totalThreads = transfersPerDirection * 2;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CyclicBarrier barrier = new CyclicBarrier(totalThreads);

        AtomicInteger w1ToW2Success = new AtomicInteger();
        AtomicInteger w2ToW1Success = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();

        // Group A: W1 -> W2 (10 units each)
        for (int i = 0; i < transfersPerDirection; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    transferService.transferStock(
                            orgId,
                            actorUserId,
                            new CreateTransferRequest(
                                    warehouse1.getId(),
                                    warehouse2.getId(),
                                    List.of(new TransferItemRequest(product1.getId(), 10L)),
                                    "W1 to W2"
                            ),
                            null
                    );
                    w1ToW2Success.incrementAndGet();
                } catch (Exception ex) {
                    failureCount.incrementAndGet();
                }
            }));
        }

        // Group B: W2 -> W1 (10 units each)
        for (int i = 0; i < transfersPerDirection; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    transferService.transferStock(
                            orgId,
                            actorUserId,
                            new CreateTransferRequest(
                                    warehouse2.getId(),
                                    warehouse1.getId(),
                                    List.of(new TransferItemRequest(product1.getId(), 10L)),
                                    "W2 to W1"
                            ),
                            null
                    );
                    w2ToW1Success.incrementAndGet();
                } catch (Exception ex) {
                    failureCount.incrementAndGet();
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(failureCount.get()).isZero();
        assertThat(w1ToW2Success.get()).isEqualTo(transfersPerDirection);
        assertThat(w2ToW1Success.get()).isEqualTo(transfersPerDirection);

        InventoryBalance w1Bal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product1.getId(), warehouse1.getId()).orElseThrow();
        InventoryBalance w2Bal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product1.getId(), warehouse2.getId()).orElseThrow();

        // 100 - (6*10) + (6*10) = 100
        assertThat(w1Bal.getOnHand()).isEqualTo(100L);
        assertThat(w2Bal.getOnHand()).isEqualTo(100L);

        ReconciliationReport report = reconciliationService.reconcile(orgId);
        assertThat(report.discrepantBalances()).isZero();
    }

    @Test
    @DisplayName("Multi-product opposite-direction transfers preserve lock-ordering consistency and physical conservation")
    void multiProductOppositeDirectionTransfers() throws Exception {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product1.getId(), warehouse1.getId(), 100L, "Initial stock"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product2.getId(), warehouse1.getId(), 100L, "Initial stock"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product1.getId(), warehouse2.getId(), 100L, "Initial stock"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product2.getId(), warehouse2.getId(), 100L, "Initial stock"), null);

        int iterations = 4;
        int totalThreads = iterations * 2;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CyclicBarrier barrier = new CyclicBarrier(totalThreads);

        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();

        // Group A: W1 -> W2 (P1: 5, P2: 10)
        for (int i = 0; i < iterations; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    transferService.transferStock(
                            orgId,
                            actorUserId,
                            new CreateTransferRequest(
                                    warehouse1.getId(),
                                    warehouse2.getId(),
                                    List.of(
                                            new TransferItemRequest(product1.getId(), 5L),
                                            new TransferItemRequest(product2.getId(), 10L)
                                    ),
                                    "A: W1 to W2"
                            ),
                            null
                    );
                    successCount.incrementAndGet();
                } catch (Exception ex) {
                    failures.incrementAndGet();
                }
            }));
        }

        // Group B: W2 -> W1 (P1: 10, P2: 5)
        for (int i = 0; i < iterations; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    transferService.transferStock(
                            orgId,
                            actorUserId,
                            new CreateTransferRequest(
                                    warehouse2.getId(),
                                    warehouse1.getId(),
                                    List.of(
                                            new TransferItemRequest(product2.getId(), 5L), // reverse order in request
                                            new TransferItemRequest(product1.getId(), 10L)
                                    ),
                                    "B: W2 to W1"
                            ),
                            null
                    );
                    successCount.incrementAndGet();
                } catch (Exception ex) {
                    failures.incrementAndGet();
                }
            }));
        }

        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        executor.shutdown();

        assertThat(failures.get()).isZero();
        assertThat(successCount.get()).isEqualTo(totalThreads);

        ReconciliationReport report = reconciliationService.reconcile(orgId);
        assertThat(report.discrepantBalances()).isZero();
    }
}
