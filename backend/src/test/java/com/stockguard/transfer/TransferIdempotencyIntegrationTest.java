package com.stockguard.transfer;

import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.dto.OpeningBalanceRequest;
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
import com.stockguard.transfer.dto.CreateTransferRequest;
import com.stockguard.transfer.dto.TransferItemRequest;
import com.stockguard.transfer.dto.TransferResponse;
import com.stockguard.transfer.service.TransferService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class TransferIdempotencyIntegrationTest {

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
    private Warehouse sourceWarehouse;
    private Warehouse destinationWarehouse;
    private Product product;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.create("Idempotent Transfer Org " + UUID.randomUUID()));
        orgId = org.getId();

        User user = User.create("idem.transfer." + UUID.randomUUID() + "@stockguard.com", "hash");
        user = userRepository.save(user);
        actorUserId = user.getId();

        OrganizationMembership membership = OrganizationMembership.create(orgId, actorUserId, Role.OPERATOR);
        membershipRepository.save(membership);

        sourceWarehouse = warehouseRepository.save(new Warehouse(orgId, "WH-IDEM-SRC", "Idem Source", "Loc", 1));
        destinationWarehouse = warehouseRepository.save(new Warehouse(orgId, "WH-IDEM-DST", "Idem Dest", "Loc", 2));

        product = productRepository.save(new Product(orgId, "SKU-IDEM-" + UUID.randomUUID().toString().substring(0, 6), "Idem Product", null));

        // Initial stock = 200
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product.getId(), sourceWarehouse.getId(), 200L, "Initial stock"), null);
    }

    @Test
    @DisplayName("Identical transfer request with same idempotency key replays original committed response without mutating stock")
    void identicalIdempotencyReplay() {
        String key = "test-idem-transfer-1-" + UUID.randomUUID();

        CreateTransferRequest request = new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(new TransferItemRequest(product.getId(), 30L)),
                "Original transfer"
        );

        // First attempt
        TransferResponse response1 = transferService.transferStock(orgId, actorUserId, request, key);
        assertThat(response1).isNotNull();
        assertThat(response1.id()).isNotNull();

        // Check balances after first attempt
        InventoryBalance srcBal1 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product.getId(), sourceWarehouse.getId()).orElseThrow();
        InventoryBalance dstBal1 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product.getId(), destinationWarehouse.getId()).orElseThrow();
        assertThat(srcBal1.getOnHand()).isEqualTo(170L);
        assertThat(dstBal1.getOnHand()).isEqualTo(30L);

        // Verify persisted response_status is 201 Created
        Integer recordedStatus = jdbcTemplate.queryForObject(
                "SELECT response_status FROM idempotency_records WHERE organization_id = ? AND idempotency_key = ?",
                Integer.class,
                orgId,
                key
        );
        assertThat(recordedStatus).isEqualTo(201);

        int transfersCount1 = jdbcTemplate.queryForObject("SELECT count(*) FROM transfers WHERE organization_id = ?", Integer.class, orgId);
        int ledgerCount1 = jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ?", Integer.class, orgId);

        // Replay attempt with same key and exact same payload
        TransferResponse response2 = transferService.transferStock(orgId, actorUserId, request, key);

        assertThat(response2.id()).isEqualTo(response1.id());
        assertThat(response2.transactionId()).isEqualTo(response1.transactionId());
        assertThat(response2.items()).hasSize(1);
        assertThat(response2.items().getFirst().id()).isEqualTo(response1.items().getFirst().id());

        // Check balances are UNCHANGED
        InventoryBalance srcBal2 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product.getId(), sourceWarehouse.getId()).orElseThrow();
        InventoryBalance dstBal2 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product.getId(), destinationWarehouse.getId()).orElseThrow();
        assertThat(srcBal2.getOnHand()).isEqualTo(170L);
        assertThat(dstBal2.getOnHand()).isEqualTo(30L);

        int transfersCount2 = jdbcTemplate.queryForObject("SELECT count(*) FROM transfers WHERE organization_id = ?", Integer.class, orgId);
        int ledgerCount2 = jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ?", Integer.class, orgId);

        assertThat(transfersCount2).isEqualTo(transfersCount1);
        assertThat(ledgerCount2).isEqualTo(ledgerCount1);
    }

    @Test
    @DisplayName("Idempotency key reuse with different request payload is rejected with IdempotencyConflictException (HTTP 409)")
    void idempotencyPayloadMismatchRejectsWith409() {
        String key = "test-idem-mismatch-" + UUID.randomUUID();

        CreateTransferRequest originalRequest = new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(new TransferItemRequest(product.getId(), 25L)),
                "Original transfer"
        );

        transferService.transferStock(orgId, actorUserId, originalRequest, key);

        // Second request uses same key but different quantity (40L instead of 25L)
        CreateTransferRequest alteredRequest = new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(new TransferItemRequest(product.getId(), 40L)),
                "Altered transfer"
        );

        assertThatThrownBy(() -> transferService.transferStock(orgId, actorUserId, alteredRequest, key))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different request payload");
    }

    @Test
    @DisplayName("Concurrent duplicate requests serialize on idempotency record: one executes, others replay response")
    void concurrentDuplicateRequestsSerialized() throws Exception {
        String key = "test-idem-concurrent-" + UUID.randomUUID();

        CreateTransferRequest request = new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(new TransferItemRequest(product.getId(), 20L)),
                "Concurrent duplicate transfer"
        );

        int threadCount = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);

        List<TransferResponse> responses = new CopyOnWriteArrayList<>();
        AtomicInteger failures = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    barrier.await();
                    TransferResponse resp = transferService.transferStock(orgId, actorUserId, request, key);
                    responses.add(resp);
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
        assertThat(responses).hasSize(threadCount);

        // All threads received the identical transfer ID
        UUID firstTransferId = responses.getFirst().id();
        for (TransferResponse resp : responses) {
            assertThat(resp.id()).isEqualTo(firstTransferId);
        }

        // Only ONE transfer was recorded in the database
        int transfersCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transfers WHERE organization_id = ? AND id = ?",
                Integer.class, orgId, firstTransferId
        );
        assertThat(transfersCount).isEqualTo(1);

        // Only 20 units deducted from source warehouse (not 8 * 20 = 160)
        InventoryBalance srcBal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product.getId(), sourceWarehouse.getId()).orElseThrow();
        InventoryBalance dstBal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product.getId(), destinationWarehouse.getId()).orElseThrow();

        assertThat(srcBal.getOnHand()).isEqualTo(180L);
        assertThat(dstBal.getOnHand()).isEqualTo(20L);
    }
}
