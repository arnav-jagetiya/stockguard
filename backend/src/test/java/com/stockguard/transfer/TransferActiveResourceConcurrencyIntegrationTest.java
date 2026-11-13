package com.stockguard.transfer;

import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.dto.OpeningBalanceRequest;
import com.stockguard.inventory.repository.InventoryBalanceRepository;
import com.stockguard.inventory.repository.JdbcInventoryOperations;
import com.stockguard.inventory.service.InventoryService;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.product.domain.Product;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.product.service.ProductService;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.shared.exception.InvalidOperationException;
import com.stockguard.transfer.dto.CreateTransferRequest;
import com.stockguard.transfer.dto.TransferItemRequest;
import com.stockguard.transfer.dto.TransferResponse;
import com.stockguard.transfer.service.TransferService;
import com.stockguard.warehouse.domain.Warehouse;
import com.stockguard.warehouse.repository.WarehouseRepository;
import com.stockguard.warehouse.service.WarehouseService;
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
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class TransferActiveResourceConcurrencyIntegrationTest {

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
    private WarehouseService warehouseService;

    @Autowired
    private ProductService productService;

    @Autowired
    private InventoryService inventoryService;

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

    @Autowired
    private JdbcInventoryOperations jdbcInventoryOperations;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID orgId;
    private UUID actorUserId;
    private Warehouse sourceWarehouse;
    private Warehouse destinationWarehouse;
    private Product product;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.create("Active Resource Test Org " + UUID.randomUUID()));
        orgId = org.getId();

        User user = User.create("resource.actor." + UUID.randomUUID() + "@stockguard.com", "hash");
        user = userRepository.save(user);
        actorUserId = user.getId();

        OrganizationMembership membership = OrganizationMembership.create(orgId, actorUserId, Role.OPERATOR);
        membershipRepository.save(membership);

        sourceWarehouse = warehouseRepository.save(new Warehouse(orgId, "WH-SRC-" + UUID.randomUUID().toString().substring(0, 6), "Source", "Loc A", 1));
        destinationWarehouse = warehouseRepository.save(new Warehouse(orgId, "WH-DST-" + UUID.randomUUID().toString().substring(0, 6), "Dest", "Loc B", 2));

        product = productRepository.save(new Product(orgId, "SKU-ACT-" + UUID.randomUUID().toString().substring(0, 6), "Active Prod", null));

        // Initial stock = 200 at source warehouse
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product.getId(), sourceWarehouse.getId(), 200L, "Initial stock"), null);
    }

    @Test
    @DisplayName("Deactivation of warehouse committed before transfer obtains lock rejects without mutations")
    void warehouseDeactivationCommittedBeforeTransferRejects() {
        // Deactivate source warehouse
        warehouseService.deactivateWarehouse(orgId, sourceWarehouse.getId());

        int initialTransfersCount = jdbcTemplate.queryForObject("SELECT count(*) FROM transfers WHERE organization_id = ?", Integer.class, orgId);
        int initialLedgersCount = jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ?", Integer.class, orgId);

        CreateTransferRequest request = new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(new TransferItemRequest(product.getId(), 20L)),
                "Transfer from inactive warehouse"
        );

        assertThatThrownBy(() -> transferService.transferStock(orgId, actorUserId, request, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Source warehouse is inactive");

        // Verify no transfers or ledger entries were created
        int finalTransfersCount = jdbcTemplate.queryForObject("SELECT count(*) FROM transfers WHERE organization_id = ?", Integer.class, orgId);
        int finalLedgersCount = jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ?", Integer.class, orgId);
        assertThat(finalTransfersCount).isEqualTo(initialTransfersCount);
        assertThat(finalLedgersCount).isEqualTo(initialLedgersCount);

        // Verify balance untouched
        InventoryBalance srcBal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product.getId(), sourceWarehouse.getId()).orElseThrow();
        assertThat(srcBal.getOnHand()).isEqualTo(200L);
    }

    @Test
    @DisplayName("Deactivation of product committed before transfer obtains lock rejects without mutations")
    void productDeactivationCommittedBeforeTransferRejects() {
        // Deactivate product
        productService.deactivateProduct(orgId, product.getId());

        int initialTransfersCount = jdbcTemplate.queryForObject("SELECT count(*) FROM transfers WHERE organization_id = ?", Integer.class, orgId);
        int initialLedgersCount = jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ?", Integer.class, orgId);

        CreateTransferRequest request = new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(new TransferItemRequest(product.getId(), 15L)),
                "Transfer inactive product"
        );

        assertThatThrownBy(() -> transferService.transferStock(orgId, actorUserId, request, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Product is inactive");

        int finalTransfersCount = jdbcTemplate.queryForObject("SELECT count(*) FROM transfers WHERE organization_id = ?", Integer.class, orgId);
        int finalLedgersCount = jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ?", Integer.class, orgId);
        assertThat(finalTransfersCount).isEqualTo(initialTransfersCount);
        assertThat(finalLedgersCount).isEqualTo(initialLedgersCount);

        InventoryBalance srcBal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product.getId(), sourceWarehouse.getId()).orElseThrow();
        assertThat(srcBal.getOnHand()).isEqualTo(200L);
    }

    @Test
    @DisplayName("Transfer holding FOR SHARE lock on warehouse causes concurrent deactivation to wait until transfer completes")
    void transferHoldingResourceLockCausesConcurrentWarehouseDeactivationToWait() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch transferCommitLatch = new CountDownLatch(1);
        AtomicBoolean deactivationCompletedBeforeCommit = new AtomicBoolean(false);

        // Thread 1: Acquire FOR SHARE lock on warehouse in independent transaction
        Future<?> transferFuture = executor.submit(() -> {
            TransactionTemplate tx1 = new TransactionTemplate(transactionManager);
            tx1.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx1.executeWithoutResult(status -> {
                // Acquire FOR SHARE lock deterministically
                jdbcInventoryOperations.lockAndGetWarehouses(orgId, sourceWarehouse.getId(), destinationWarehouse.getId());
                lockAcquiredLatch.countDown();

                // Wait for main thread to verify Thread 2 is blocked before committing
                try {
                    transferCommitLatch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        });

        // Wait until Thread 1 has acquired FOR SHARE lock
        assertThat(lockAcquiredLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread 2: Attempt deactivation of source warehouse (requires exclusive row lock UPDATE)
        Future<?> deactivationFuture = executor.submit(() -> {
            TransactionTemplate tx2 = new TransactionTemplate(transactionManager);
            tx2.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx2.executeWithoutResult(status -> {
                warehouseService.deactivateWarehouse(orgId, sourceWarehouse.getId());
                warehouseRepository.flush(); // Flush forces Hibernate to send UPDATE to PostgreSQL immediately
                deactivationCompletedBeforeCommit.set(true);
            });
        });

        // Verify Thread 2 is BLOCKED waiting for Thread 1's FOR SHARE lock
        assertThatThrownBy(() -> deactivationFuture.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        assertThat(deactivationCompletedBeforeCommit.get()).isFalse();

        // Allow Thread 1 to commit
        transferCommitLatch.countDown();
        transferFuture.get(5, TimeUnit.SECONDS);

        // Now Thread 2 unblocks and completes deactivation
        deactivationFuture.get(5, TimeUnit.SECONDS);
        assertThat(deactivationCompletedBeforeCommit.get()).isTrue();

        executor.shutdown();

        // Confirm warehouse is now deactivated
        Warehouse updated = warehouseRepository.findById(sourceWarehouse.getId()).orElseThrow();
        assertThat(updated.isActive()).isFalse();
    }

    @Test
    @DisplayName("Transfer holding FOR SHARE lock on product causes concurrent deactivation to wait until transfer completes")
    void transferHoldingResourceLockCausesConcurrentProductDeactivationToWait() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch lockAcquiredLatch = new CountDownLatch(1);
        CountDownLatch transferCommitLatch = new CountDownLatch(1);
        AtomicBoolean deactivationCompletedBeforeCommit = new AtomicBoolean(false);

        // Thread 1: Acquire FOR SHARE lock on product in independent transaction
        Future<?> transferFuture = executor.submit(() -> {
            TransactionTemplate tx1 = new TransactionTemplate(transactionManager);
            tx1.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx1.executeWithoutResult(status -> {
                jdbcInventoryOperations.lockAndGetProducts(orgId, List.of(product.getId()));
                lockAcquiredLatch.countDown();

                try {
                    transferCommitLatch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            });
        });

        // Wait until Thread 1 has acquired FOR SHARE lock
        assertThat(lockAcquiredLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Thread 2: Attempt deactivation of product (requires exclusive row lock UPDATE)
        Future<?> deactivationFuture = executor.submit(() -> {
            TransactionTemplate tx2 = new TransactionTemplate(transactionManager);
            tx2.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx2.executeWithoutResult(status -> {
                productService.deactivateProduct(orgId, product.getId());
                productRepository.flush(); // Flush forces Hibernate to send UPDATE to PostgreSQL immediately
                deactivationCompletedBeforeCommit.set(true);
            });
        });

        // Verify Thread 2 is BLOCKED waiting for Thread 1's FOR SHARE lock
        assertThatThrownBy(() -> deactivationFuture.get(300, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
        assertThat(deactivationCompletedBeforeCommit.get()).isFalse();

        // Allow Thread 1 to commit
        transferCommitLatch.countDown();
        transferFuture.get(5, TimeUnit.SECONDS);

        // Now Thread 2 unblocks and completes deactivation
        deactivationFuture.get(5, TimeUnit.SECONDS);
        assertThat(deactivationCompletedBeforeCommit.get()).isTrue();

        executor.shutdown();

        Product updated = productRepository.findById(product.getId()).orElseThrow();
        assertThat(updated.isActive()).isFalse();
    }

    @Test
    @DisplayName("Normal concurrent transfers do not block each other on resource FOR SHARE locks")
    void normalConcurrentTransfersDoNotBlockEachOther() throws Exception {
        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        List<Future<TransferResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return transferService.transferStock(
                        orgId,
                        actorUserId,
                        new CreateTransferRequest(
                                sourceWarehouse.getId(),
                                destinationWarehouse.getId(),
                                List.of(new TransferItemRequest(product.getId(), 10L)),
                                "Concurrent transfer"
                        ),
                        null
                );
            }));
        }

        for (Future<TransferResponse> f : futures) {
            TransferResponse resp = f.get(10, TimeUnit.SECONDS);
            assertThat(resp).isNotNull();
            assertThat(resp.id()).isNotNull();
        }

        executor.shutdown();

        // Initial 200 - (4 * 10) = 160
        InventoryBalance srcBal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product.getId(), sourceWarehouse.getId()).orElseThrow();
        InventoryBalance dstBal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product.getId(), destinationWarehouse.getId()).orElseThrow();

        assertThat(srcBal.getOnHand()).isEqualTo(160L);
        assertThat(dstBal.getOnHand()).isEqualTo(40L);
    }
}
