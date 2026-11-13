package com.stockguard.inventory;

import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.dto.AdjustStockRequest;
import com.stockguard.inventory.dto.OpeningBalanceRequest;
import com.stockguard.inventory.dto.ReceiveStockRequest;
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
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class InventoryConcurrencyIntegrationTest {

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

    private UUID orgId;
    private UUID actorUserId;
    private UUID productId;
    private UUID warehouseId;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.create("Concurrent Org " + UUID.randomUUID()));
        orgId = org.getId();

        User user = User.create("concurrent.actor." + UUID.randomUUID() + "@stockguard.com", "hash");
        user = userRepository.save(user);
        actorUserId = user.getId();

        OrganizationMembership membership = OrganizationMembership.create(orgId, actorUserId, Role.OPERATOR);
        membershipRepository.save(membership);

        Product product = productRepository.save(new Product(orgId, "SKU-CONC-" + UUID.randomUUID().toString().substring(0, 8), "Conc Product", null));
        productId = product.getId();

        Warehouse warehouse = warehouseRepository.save(new Warehouse(orgId, "WH-CONC-" + UUID.randomUUID().toString().substring(0, 6), "Conc WH", "Location", 1));
        warehouseId = warehouse.getId();
    }

    @Test
    @DisplayName("Concurrent receipts on the same balance row execute without lost updates or race conditions")
    void concurrentReceiptsNoLostUpdates() throws Exception {
        int threadCount = 10;
        long unitsPerThread = 5L;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    inventoryService.receiveStock(
                            orgId,
                            actorUserId,
                            new ReceiveStockRequest(productId, warehouseId, unitsPerThread, null, "Concurrent receipt"),
                            null
                    );
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        boolean completed = doneLatch.await(20, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();
        assertThat(successCount.get()).isEqualTo(threadCount);

        // Verify balance equals exact sum of thread increments
        InventoryBalance balance = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productId, warehouseId).orElseThrow();
        assertThat(balance.getOnHand()).isEqualTo(threadCount * unitsPerThread);

        // Verify exactly 10 ledger entries were inserted
        Integer ledgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger_entries WHERE organization_id = ? AND product_id = ?",
                Integer.class, orgId, productId
        );
        assertThat(ledgerCount).isEqualTo(threadCount);
    }

    @Test
    @DisplayName("Canonical lock ordering locks rows in product_id ASC, warehouse_id ASC avoiding deadlocks")
    void canonicalLockOrderingAvoidsDeadlocks() {
        Product p1 = productRepository.save(new Product(orgId, "SKU-A-" + UUID.randomUUID().toString().substring(0, 6), "P1", null));
        Product p2 = productRepository.save(new Product(orgId, "SKU-B-" + UUID.randomUUID().toString().substring(0, 6), "P2", null));

        Warehouse w1 = warehouseRepository.save(new Warehouse(orgId, "WH-A-" + UUID.randomUUID().toString().substring(0, 6), "W1", "Loc", 1));
        Warehouse w2 = warehouseRepository.save(new Warehouse(orgId, "WH-B-" + UUID.randomUUID().toString().substring(0, 6), "W2", "Loc", 2));

        // Unordered list of keys
        List<JdbcInventoryOperations.ProductWarehouseKey> keys = List.of(
                new JdbcInventoryOperations.ProductWarehouseKey(p2.getId(), w2.getId()),
                new JdbcInventoryOperations.ProductWarehouseKey(p1.getId(), w2.getId()),
                new JdbcInventoryOperations.ProductWarehouseKey(p2.getId(), w1.getId()),
                new JdbcInventoryOperations.ProductWarehouseKey(p1.getId(), w1.getId())
        );

        List<InventoryBalance> locked = jdbcInventoryOperations.lockAndGetBalances(orgId, keys);
        assertThat(locked).hasSize(4);

        // Verify strictly sorted by product_id ASC, then warehouse_id ASC
        for (int i = 0; i < locked.size() - 1; i++) {
            InventoryBalance curr = locked.get(i);
            InventoryBalance next = locked.get(i + 1);

            int prodCmp = curr.getProductId().compareTo(next.getProductId());
            if (prodCmp == 0) {
                assertThat(curr.getWarehouseId().compareTo(next.getWarehouseId())).isLessThanOrEqualTo(0);
            } else {
                assertThat(prodCmp).isLessThan(0);
            }
        }
    }

    @Test
    @DisplayName("Two concurrent requests attempt the first opening balance: exactly one succeeds and exactly one OPENING_BALANCE transaction is recorded")
    void concurrentFirstOpeningBalanceOnlyOneSucceeds() throws Exception {
        Product newProduct = productRepository.save(new Product(orgId, "SKU-OPEN-" + UUID.randomUUID().toString().substring(0, 6), "Opening Product", null));
        UUID newProdId = newProduct.getId();

        OpeningBalanceRequest req1 = new OpeningBalanceRequest(newProdId, warehouseId, 50L, "Concurrent opening 1");
        OpeningBalanceRequest req2 = new OpeningBalanceRequest(newProdId, warehouseId, 75L, "Concurrent opening 2");

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

        // Thread 1
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                inventoryService.recordOpeningBalance(orgId, actorUserId, req1, null);
                successCount.incrementAndGet();
            } catch (InvalidOperationException ex) {
                if (ex.getMessage().contains("Opening balance has already been established")) {
                    conflictCount.incrementAndGet();
                } else {
                    unexpectedErrors.add(ex);
                }
            } catch (Throwable t) {
                unexpectedErrors.add(t);
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2
        executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                inventoryService.recordOpeningBalance(orgId, actorUserId, req2, null);
                successCount.incrementAndGet();
            } catch (InvalidOperationException ex) {
                if (ex.getMessage().contains("Opening balance has already been established")) {
                    conflictCount.incrementAndGet();
                } else {
                    unexpectedErrors.add(ex);
                }
            } catch (Throwable t) {
                unexpectedErrors.add(t);
            } finally {
                doneLatch.countDown();
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        boolean completed = doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(unexpectedErrors).isEmpty();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // Exactly 1 OPENING_BALANCE transaction recorded
        Integer txCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM inventory_transactions t
                JOIN inventory_ledger_entries l ON t.organization_id = l.organization_id AND t.id = l.transaction_id
                WHERE t.organization_id = ? AND t.transaction_type = 'OPENING_BALANCE' AND l.product_id = ? AND l.warehouse_id = ?
                """,
                Integer.class, orgId, newProdId, warehouseId
        );
        assertThat(txCount).isEqualTo(1);

        // Exactly 1 ledger entry recorded
        Integer ledgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger_entries WHERE organization_id = ? AND product_id = ? AND warehouse_id = ?",
                Integer.class, orgId, newProdId, warehouseId
        );
        assertThat(ledgerCount).isEqualTo(1);

        // Balance matches the successful thread's quantity (either 50 or 75)
        InventoryBalance balance = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, newProdId, warehouseId).orElseThrow();
        assertThat(balance.getOnHand()).isIn(50L, 75L);
        assertThat(balance.getReserved()).isEqualTo(0L);
    }
}
