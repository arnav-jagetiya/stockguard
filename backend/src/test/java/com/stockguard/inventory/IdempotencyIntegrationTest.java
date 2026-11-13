package com.stockguard.inventory;

import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.dto.InventoryTransactionResponse;
import com.stockguard.inventory.dto.ReceiveStockRequest;
import com.stockguard.inventory.repository.InventoryBalanceRepository;
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
import com.stockguard.shared.idempotency.IdempotencyConflictException;
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

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class IdempotencyIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    private UUID orgId;
    private UUID actorUserId;
    private UUID productId;
    private UUID warehouseId;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.create("Idempotent Org " + UUID.randomUUID()));
        orgId = org.getId();

        User user = User.create("idemp.actor." + UUID.randomUUID() + "@stockguard.com", "hash");
        user = userRepository.save(user);
        actorUserId = user.getId();

        OrganizationMembership membership = OrganizationMembership.create(orgId, actorUserId, Role.OPERATOR);
        membershipRepository.save(membership);

        Product product = productRepository.save(new Product(orgId, "SKU-IDEMP-" + UUID.randomUUID().toString().substring(0, 8), "Idemp Product", null));
        productId = product.getId();

        Warehouse warehouse = warehouseRepository.save(new Warehouse(orgId, "WH-IDEMP-" + UUID.randomUUID().toString().substring(0, 6), "Idemp WH", "Dock 1", 1));
        warehouseId = warehouse.getId();
    }

    @Test
    @DisplayName("Identical request with same idempotency key replays stored response without double-mutating inventory")
    void sameKeySamePayloadReplaysResponse() {
        String key = "receipt-key-" + UUID.randomUUID();
        ReceiveStockRequest request = new ReceiveStockRequest(productId, warehouseId, 25L, null, "PO-500");

        // First call
        InventoryTransactionResponse resp1 = inventoryService.receiveStock(orgId, actorUserId, request, key);
        assertThat(resp1).isNotNull();

        InventoryBalance balanceAfterFirst = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productId, warehouseId).orElseThrow();
        assertThat(balanceAfterFirst.getOnHand()).isEqualTo(25L);

        // Replay call with exact same key and payload
        InventoryTransactionResponse resp2 = inventoryService.receiveStock(orgId, actorUserId, request, key);
        assertThat(resp2.id()).isEqualTo(resp1.id());
        assertThat(resp2.transactionType()).isEqualTo("RECEIPT");

        // Inventory balance MUST NOT have changed (remains 25, not 50)
        InventoryBalance balanceAfterReplay = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productId, warehouseId).orElseThrow();
        assertThat(balanceAfterReplay.getOnHand()).isEqualTo(25L);

        // Exactly 1 ledger entry exists
        Integer ledgerCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger_entries WHERE organization_id = ? AND product_id = ?",
                Integer.class, orgId, productId
        );
        assertThat(ledgerCount).isEqualTo(1);
    }

    @Test
    @DisplayName("Same idempotency key with differing request payload fails with 409 Conflict")
    void sameKeyDifferentPayloadThrowsConflict() {
        String key = "conflict-key-" + UUID.randomUUID();
        ReceiveStockRequest originalReq = new ReceiveStockRequest(productId, warehouseId, 20L, null, "First payload");
        inventoryService.receiveStock(orgId, actorUserId, originalReq, key);

        // Differing payload with same key
        ReceiveStockRequest alteredReq = new ReceiveStockRequest(productId, warehouseId, 50L, null, "Altered payload");

        assertThatThrownBy(() -> inventoryService.receiveStock(orgId, actorUserId, alteredReq, key))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("already been used with a different request payload");
    }

    @Test
    @DisplayName("Concurrent duplicate requests with the same key serialize and only execute mutation once")
    void concurrentDuplicateRequestsOnlyExecuteOnce() throws Exception {
        String sharedKey = "concurrent-key-" + UUID.randomUUID();
        ReceiveStockRequest request = new ReceiveStockRequest(productId, warehouseId, 10L, null, "Concurrent test");

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<InventoryTransactionResponse> responses = new CopyOnWriteArrayList<>();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    InventoryTransactionResponse resp = inventoryService.receiveStock(orgId, actorUserId, request, sharedKey);
                    responses.add(resp);
                } catch (Throwable t) {
                    errors.add(t);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        doneLatch.await(15, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one executed mutation; any replays return the same transaction ID
        InventoryBalance balance = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productId, warehouseId).orElseThrow();
        assertThat(balance.getOnHand()).isEqualTo(10L); // Exactly 10, not 40!

        // All returned responses must share the exact same transaction ID
        if (!responses.isEmpty()) {
            UUID firstTxId = responses.get(0).id();
            for (InventoryTransactionResponse resp : responses) {
                assertThat(resp.id()).isEqualTo(firstTxId);
            }
        }
    }
}
