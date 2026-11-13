package com.stockguard.transfer;

import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.dto.LedgerEntryDto;
import com.stockguard.inventory.dto.OpeningBalanceRequest;
import com.stockguard.inventory.dto.ReconciliationReport;
import com.stockguard.inventory.repository.InventoryBalanceRepository;
import com.stockguard.inventory.repository.JdbcInventoryOperations;
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
import com.stockguard.shared.exception.InvalidOperationException;
import com.stockguard.shared.exception.ResourceNotFoundException;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class TransferCoreIntegrationTest {

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

    @Autowired
    private JdbcInventoryOperations jdbcInventoryOperations;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID orgId;
    private UUID actorUserId;
    private Warehouse sourceWarehouse;
    private Warehouse destinationWarehouse;
    private Product product1;
    private Product product2;
    private Product product3;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.create("Transfer Core Org " + UUID.randomUUID()));
        orgId = org.getId();

        User user = User.create("transfer.actor." + UUID.randomUUID() + "@stockguard.com", "hash");
        user = userRepository.save(user);
        actorUserId = user.getId();

        OrganizationMembership membership = OrganizationMembership.create(orgId, actorUserId, Role.OPERATOR);
        membershipRepository.save(membership);

        sourceWarehouse = warehouseRepository.save(new Warehouse(orgId, "SRC-WH-" + UUID.randomUUID().toString().substring(0, 6), "Source WH", "Loc A", 1));
        destinationWarehouse = warehouseRepository.save(new Warehouse(orgId, "DST-WH-" + UUID.randomUUID().toString().substring(0, 6), "Dest WH", "Loc B", 2));

        product1 = productRepository.save(new Product(orgId, "SKU-T1-" + UUID.randomUUID().toString().substring(0, 6), "Transfer Prod 1", null));
        product2 = productRepository.save(new Product(orgId, "SKU-T2-" + UUID.randomUUID().toString().substring(0, 6), "Transfer Prod 2", null));
        product3 = productRepository.save(new Product(orgId, "SKU-T3-" + UUID.randomUUID().toString().substring(0, 6), "Transfer Prod 3", null));
    }

    @Test
    @DisplayName("Single-product transfer successfully updates balances, creates transfer, transaction, and dual-delta ledger pair")
    void singleProductTransferSuccess() {
        // 1. Establish opening stock of 100 at source warehouse
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product1.getId(), sourceWarehouse.getId(), 100L, "Initial stock"), null);

        // 2. Transfer 35 units to destination warehouse
        CreateTransferRequest request = new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(new TransferItemRequest(product1.getId(), 35L)),
                "Inter-warehouse replenishment"
        );

        TransferResponse response = transferService.transferStock(orgId, actorUserId, request, null);

        assertThat(response).isNotNull();
        assertThat(response.id()).isNotNull();
        assertThat(response.organizationId()).isEqualTo(orgId);
        assertThat(response.sourceWarehouseId()).isEqualTo(sourceWarehouse.getId());
        assertThat(response.destinationWarehouseId()).isEqualTo(destinationWarehouse.getId());
        assertThat(response.notes()).isEqualTo("Inter-warehouse replenishment");
        assertThat(response.actorUserId()).isEqualTo(actorUserId);
        assertThat(response.transactionId()).isNotNull();
        assertThat(response.items()).hasSize(1);

        UUID transferItemId = response.items().getFirst().id();
        assertThat(response.items().getFirst().productId()).isEqualTo(product1.getId());
        assertThat(response.items().getFirst().quantity()).isEqualTo(35L);

        // 3. Verify balances
        InventoryBalance srcBal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product1.getId(), sourceWarehouse.getId()).orElseThrow();
        InventoryBalance dstBal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product1.getId(), destinationWarehouse.getId()).orElseThrow();

        assertThat(srcBal.getOnHand()).isEqualTo(65L);
        assertThat(srcBal.getReserved()).isEqualTo(0L);
        assertThat(dstBal.getOnHand()).isEqualTo(35L);
        assertThat(dstBal.getReserved()).isEqualTo(0L);

        // 4. Verify ledger entries: exactly 2 entries (1 OUT, 1 IN)
        List<JdbcInventoryOperations.DetailedLedgerEntry> ledgers = jdbcInventoryOperations.getDetailedLedgerEntriesForTransaction(orgId, response.transactionId());
        assertThat(ledgers).hasSize(2);

        JdbcInventoryOperations.DetailedLedgerEntry outEntry = ledgers.stream().filter(l -> "OUT".equals(l.transferDirection())).findFirst().orElseThrow();
        assertThat(outEntry.entryNumber()).isEqualTo(1);
        assertThat(outEntry.productId()).isEqualTo(product1.getId());
        assertThat(outEntry.warehouseId()).isEqualTo(sourceWarehouse.getId());
        assertThat(outEntry.onHandDelta()).isEqualTo(-35L);
        assertThat(outEntry.reservedDelta()).isEqualTo(0L);
        assertThat(outEntry.transferItemId()).isEqualTo(transferItemId);

        JdbcInventoryOperations.DetailedLedgerEntry inEntry = ledgers.stream().filter(l -> "IN".equals(l.transferDirection())).findFirst().orElseThrow();
        assertThat(inEntry.entryNumber()).isEqualTo(2);
        assertThat(inEntry.productId()).isEqualTo(product1.getId());
        assertThat(inEntry.warehouseId()).isEqualTo(destinationWarehouse.getId());
        assertThat(inEntry.onHandDelta()).isEqualTo(35L);
        assertThat(inEntry.reservedDelta()).isEqualTo(0L);
        assertThat(inEntry.transferItemId()).isEqualTo(transferItemId);
    }

    @Test
    @DisplayName("Multi-product transfer executes atomically with sequential ledger numbering across all lines")
    void multiProductTransferSuccess() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product1.getId(), sourceWarehouse.getId(), 100L, "Initial stock"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product2.getId(), sourceWarehouse.getId(), 200L, "Initial stock"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product3.getId(), sourceWarehouse.getId(), 300L, "Initial stock"), null);

        CreateTransferRequest request = new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(
                        new TransferItemRequest(product1.getId(), 20L),
                        new TransferItemRequest(product2.getId(), 50L),
                        new TransferItemRequest(product3.getId(), 80L)
                ),
                "Bulk multi-item transfer"
        );

        TransferResponse response = transferService.transferStock(orgId, actorUserId, request, null);
        assertThat(response.items()).hasSize(3);

        // Verify balances across all products
        assertThat(balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product1.getId(), sourceWarehouse.getId()).orElseThrow().getOnHand()).isEqualTo(80L);
        assertThat(balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product1.getId(), destinationWarehouse.getId()).orElseThrow().getOnHand()).isEqualTo(20L);

        assertThat(balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product2.getId(), sourceWarehouse.getId()).orElseThrow().getOnHand()).isEqualTo(150L);
        assertThat(balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product2.getId(), destinationWarehouse.getId()).orElseThrow().getOnHand()).isEqualTo(50L);

        assertThat(balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product3.getId(), sourceWarehouse.getId()).orElseThrow().getOnHand()).isEqualTo(220L);
        assertThat(balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product3.getId(), destinationWarehouse.getId()).orElseThrow().getOnHand()).isEqualTo(80L);

        // Verify ledger entries: 3 products * 2 = 6 entries, strictly numbered 1..6
        List<JdbcInventoryOperations.DetailedLedgerEntry> ledgers = jdbcInventoryOperations.getDetailedLedgerEntriesForTransaction(orgId, response.transactionId());
        assertThat(ledgers).hasSize(6);

        for (int i = 0; i < 6; i++) {
            assertThat(ledgers.get(i).entryNumber()).isEqualTo(i + 1);
            assertThat(ledgers.get(i).reservedDelta()).isEqualTo(0L);
        }

        // Total physical on_hand delta across the transaction must be exactly 0
        long totalDelta = ledgers.stream().mapToLong(JdbcInventoryOperations.DetailedLedgerEntry::onHandDelta).sum();
        assertThat(totalDelta).isZero();
    }

    @Test
    @DisplayName("Destination balance row is created automatically if it did not exist prior to transfer")
    void missingDestinationBalanceRowCreated() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product1.getId(), sourceWarehouse.getId(), 50L, "Initial stock"), null);

        // Explicitly confirm no destination row exists
        assertThat(balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product1.getId(), destinationWarehouse.getId())).isEmpty();

        CreateTransferRequest request = new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(new TransferItemRequest(product1.getId(), 25L)),
                "Transfer to cold warehouse"
        );

        transferService.transferStock(orgId, actorUserId, request, null);

        InventoryBalance dstBal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product1.getId(), destinationWarehouse.getId()).orElseThrow();
        assertThat(dstBal.getOnHand()).isEqualTo(25L);
        assertThat(dstBal.getReserved()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Transfer fails when source available stock is insufficient")
    void insufficientAvailableStockRejected() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product1.getId(), sourceWarehouse.getId(), 10L, "Initial stock"), null);

        CreateTransferRequest request = new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(new TransferItemRequest(product1.getId(), 15L)),
                "Over-transfer"
        );

        assertThatThrownBy(() -> transferService.transferStock(orgId, actorUserId, request, null))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("Insufficient available stock");

        // Verify source and destination balances are untouched
        assertThat(balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product1.getId(), sourceWarehouse.getId()).orElseThrow().getOnHand()).isEqualTo(10L);
        assertThat(balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product1.getId(), destinationWarehouse.getId())).isEmpty();
    }

    @Test
    @DisplayName("Reserved stock is strictly protected and never consumed by transfers")
    void reservedStockProtection() {
        // Initial on_hand = 100
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product1.getId(), sourceWarehouse.getId(), 100L, "Initial stock"), null);

        // Reserve 80 units directly in DB (simulating order reservations)
        jdbcTemplate.update(
                "UPDATE inventory_balances SET reserved = 80 WHERE organization_id = ? AND product_id = ? AND warehouse_id = ?",
                orgId, product1.getId(), sourceWarehouse.getId()
        );

        // Available stock is now 100 - 80 = 20.
        // Transferring 25 units must fail because it would dip into the 80 reserved units!
        CreateTransferRequest overRequest = new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(new TransferItemRequest(product1.getId(), 25L)),
                "Try encroaching on reserved stock"
        );

        assertThatThrownBy(() -> transferService.transferStock(orgId, actorUserId, overRequest, null))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("available 20")
                .hasMessageContaining("reserved=80");

        // Transferring exactly 20 units must succeed
        CreateTransferRequest validRequest = new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(new TransferItemRequest(product1.getId(), 20L)),
                "Transfer remaining available stock"
        );

        transferService.transferStock(orgId, actorUserId, validRequest, null);

        InventoryBalance srcBal = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product1.getId(), sourceWarehouse.getId()).orElseThrow();
        assertThat(srcBal.getOnHand()).isEqualTo(80L);
        assertThat(srcBal.getReserved()).isEqualTo(80L); // 80 units strictly intact!
    }

    @Test
    @DisplayName("Transfer rejects identical source and destination warehouses")
    void distinctWarehouseInvariantRejected() {
        CreateTransferRequest request = new CreateTransferRequest(
                sourceWarehouse.getId(),
                sourceWarehouse.getId(),
                List.of(new TransferItemRequest(product1.getId(), 10L)),
                "Self-transfer"
        );

        assertThatThrownBy(() -> transferService.transferStock(orgId, actorUserId, request, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Source and destination warehouses cannot be the same");
    }

    @Test
    @DisplayName("Transfer rejects inactive resources (warehouse or product)")
    void inactiveWarehouseAndProductRejected() {
        // Inactive destination warehouse
        Warehouse inactiveWh = warehouseRepository.save(new Warehouse(orgId, "INACT-WH", "Inactive WH", "Loc", 3));
        inactiveWh.deactivate();
        warehouseRepository.save(inactiveWh);

        CreateTransferRequest reqDestInactive = new CreateTransferRequest(
                sourceWarehouse.getId(),
                inactiveWh.getId(),
                List.of(new TransferItemRequest(product1.getId(), 5L)),
                "Inactive dest"
        );

        assertThatThrownBy(() -> transferService.transferStock(orgId, actorUserId, reqDestInactive, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Destination warehouse is inactive");

        // Inactive product
        Product inactiveProd = productRepository.save(new Product(orgId, "INACT-SKU", "Inactive Prod", null));
        inactiveProd.deactivate();
        productRepository.save(inactiveProd);

        CreateTransferRequest reqProdInactive = new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(new TransferItemRequest(inactiveProd.getId(), 5L)),
                "Inactive prod"
        );

        assertThatThrownBy(() -> transferService.transferStock(orgId, actorUserId, reqProdInactive, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Product is inactive");
    }

    @Test
    @DisplayName("Transfer rejects non-existent warehouse or product IDs")
    void nonExistentWarehouseAndProductRejected() {
        UUID randomWhId = UUID.randomUUID();
        CreateTransferRequest reqBadWh = new CreateTransferRequest(
                randomWhId,
                destinationWarehouse.getId(),
                List.of(new TransferItemRequest(product1.getId(), 5L)),
                "Bad WH"
        );

        assertThatThrownBy(() -> transferService.transferStock(orgId, actorUserId, reqBadWh, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Source warehouse not found");

        UUID randomProdId = UUID.randomUUID();
        CreateTransferRequest reqBadProd = new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(new TransferItemRequest(randomProdId, 5L)),
                "Bad Prod"
        );

        assertThatThrownBy(() -> transferService.transferStock(orgId, actorUserId, reqBadProd, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product not found");
    }

    @Test
    @DisplayName("Transfer rejects duplicate product IDs in a single request")
    void duplicateProductInSingleRequestRejected() {
        CreateTransferRequest request = new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(
                        new TransferItemRequest(product1.getId(), 10L),
                        new TransferItemRequest(product1.getId(), 20L)
                ),
                "Duplicate products"
        );

        assertThatThrownBy(() -> transferService.transferStock(orgId, actorUserId, request, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Duplicate product ID in transfer request");
    }

    @Test
    @DisplayName("Transaction fully rolls back all mutations when failure occurs mid-transaction")
    void transactionRollbackOnInjectedFailure() {
        // Source has 100 units of product1 and 100 units of product2
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product1.getId(), sourceWarehouse.getId(), 100L, "Initial stock"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product2.getId(), sourceWarehouse.getId(), 100L, "Initial stock"), null);

        int initialTransfersCount = jdbcTemplate.queryForObject("SELECT count(*) FROM transfers WHERE organization_id = ?", Integer.class, orgId);
        int initialLedgersCount = jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ?", Integer.class, orgId);

        // Product 1 has 100 units, but Product 2 request asks for 500 units (insufficient!)
        CreateTransferRequest request = new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(
                        new TransferItemRequest(product1.getId(), 50L),
                        new TransferItemRequest(product2.getId(), 500L)
                ),
                "Partial failure test"
        );

        assertThatThrownBy(() -> transferService.transferStock(orgId, actorUserId, request, null))
                .isInstanceOf(InsufficientStockException.class);

        // Verify that NO partial state leaked: product1 stock at source was NOT decremented, transfers count unchanged
        assertThat(balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product1.getId(), sourceWarehouse.getId()).orElseThrow().getOnHand()).isEqualTo(100L);
        assertThat(balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, product1.getId(), destinationWarehouse.getId())).isEmpty();

        int finalTransfersCount = jdbcTemplate.queryForObject("SELECT count(*) FROM transfers WHERE organization_id = ?", Integer.class, orgId);
        int finalLedgersCount = jdbcTemplate.queryForObject("SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ?", Integer.class, orgId);

        assertThat(finalTransfersCount).isEqualTo(initialTransfersCount);
        assertThat(finalLedgersCount).isEqualTo(initialLedgersCount);
    }

    @Test
    @DisplayName("Mathematical inventory reconciliation confirms 0 discrepancies after inter-warehouse transfers")
    void mathematicalInventoryReconciliation() {
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product1.getId(), sourceWarehouse.getId(), 200L, "Initial stock"), null);
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(product2.getId(), sourceWarehouse.getId(), 150L, "Initial stock"), null);

        // Transfer 50 of P1 and 30 of P2
        transferService.transferStock(orgId, actorUserId, new CreateTransferRequest(
                sourceWarehouse.getId(),
                destinationWarehouse.getId(),
                List.of(
                        new TransferItemRequest(product1.getId(), 50L),
                        new TransferItemRequest(product2.getId(), 30L)
                ),
                "Transfer 1"
        ), null);

        // Transfer back 10 of P1 from destination to source
        transferService.transferStock(orgId, actorUserId, new CreateTransferRequest(
                destinationWarehouse.getId(),
                sourceWarehouse.getId(),
                List.of(new TransferItemRequest(product1.getId(), 10L)),
                "Transfer 2 (return)"
        ), null);

        // Execute mathematical reconciliation per ADR-004
        ReconciliationReport report = reconciliationService.reconcile(orgId);

        assertThat(report.discrepantBalances()).isZero();
        assertThat(report.discrepancies()).isEmpty();
        assertThat(report.totalBalancesInspected()).isGreaterThanOrEqualTo(2);
        assertThat(report.matchingBalances()).isEqualTo(report.totalBalancesInspected());
    }
}
