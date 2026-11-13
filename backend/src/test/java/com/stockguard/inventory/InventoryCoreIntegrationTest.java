package com.stockguard.inventory;

import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.dto.*;
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
import com.stockguard.shared.exception.InsufficientStockException;
import com.stockguard.shared.exception.InvalidOperationException;
import com.stockguard.warehouse.domain.Warehouse;
import com.stockguard.warehouse.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
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
class InventoryCoreIntegrationTest {

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
        Organization org = organizationRepository.save(Organization.create("Inventory Org " + UUID.randomUUID()));
        orgId = org.getId();

        User user = User.create("actor." + UUID.randomUUID() + "@stockguard.com", "hash");
        user = userRepository.save(user);
        actorUserId = user.getId();

        OrganizationMembership membership = OrganizationMembership.create(orgId, actorUserId, Role.OPERATOR);
        membershipRepository.save(membership);

        Product product = productRepository.save(new Product(orgId, "SKU-INV-" + UUID.randomUUID().toString().substring(0, 8), "Inventory Test Product", null));
        productId = product.getId();

        Warehouse warehouse = warehouseRepository.save(new Warehouse(orgId, "WH-INV-" + UUID.randomUUID().toString().substring(0, 6), "Inventory Test WH", "Dock 1", 1));
        warehouseId = warehouse.getId();
    }

    @Test
    @DisplayName("Opening balance establishes initial stock, updates balance, and inserts immutable ledger entry")
    void recordOpeningBalanceSuccess() {
        OpeningBalanceRequest req = new OpeningBalanceRequest(productId, warehouseId, 50L, "Initial stock opening");
        InventoryTransactionResponse tx = inventoryService.recordOpeningBalance(orgId, actorUserId, req, null);

        assertThat(tx.transactionType()).isEqualTo("OPENING_BALANCE");
        assertThat(tx.actorUserId()).isEqualTo(actorUserId);
        assertThat(tx.entries()).hasSize(1);
        assertThat(tx.entries().get(0).onHandDelta()).isEqualTo(50L);
        assertThat(tx.entries().get(0).reservedDelta()).isEqualTo(0L);

        InventoryBalance balance = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productId, warehouseId).orElseThrow();
        assertThat(balance.getOnHand()).isEqualTo(50L);
        assertThat(balance.getReserved()).isEqualTo(0L);
        assertThat(balance.getAvailable()).isEqualTo(50L);

        // Attempting a second opening balance when on_hand > 0 is rejected
        assertThatThrownBy(() -> inventoryService.recordOpeningBalance(orgId, actorUserId, req, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Opening balance has already been established");
    }

    @Test
    @DisplayName("Receive stock increments on-hand and records RECEIPT transaction with dual-delta ledger")
    void receiveStockIncrementsOnHand() {
        ReceiveStockRequest req1 = new ReceiveStockRequest(productId, warehouseId, 25L, null, "PO receipt 1");
        inventoryService.receiveStock(orgId, actorUserId, req1, null);

        ReceiveStockRequest req2 = new ReceiveStockRequest(productId, warehouseId, 15L, null, "PO receipt 2");
        InventoryTransactionResponse tx2 = inventoryService.receiveStock(orgId, actorUserId, req2, null);

        assertThat(tx2.transactionType()).isEqualTo("RECEIPT");
        assertThat(tx2.entries().get(0).onHandDelta()).isEqualTo(15L);

        InventoryBalance balance = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productId, warehouseId).orElseThrow();
        assertThat(balance.getOnHand()).isEqualTo(40L);
        assertThat(balance.getAvailable()).isEqualTo(40L);
    }

    @Test
    @DisplayName("Stock adjustments support positive and negative deltas with mandatory reason and boundary checks")
    void adjustStockPositiveAndNegativeDeltas() {
        // Initial receipt of 30 units
        inventoryService.receiveStock(orgId, actorUserId, new ReceiveStockRequest(productId, warehouseId, 30L, null, "Setup"), null);

        // Positive adjustment: +10 units
        AdjustStockRequest posReq = new AdjustStockRequest(productId, warehouseId, 10L, "Found extra stock during cycle count", null);
        inventoryService.adjustStock(orgId, actorUserId, posReq, null);

        InventoryBalance bal1 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productId, warehouseId).orElseThrow();
        assertThat(bal1.getOnHand()).isEqualTo(40L);

        // Negative adjustment: -5 units
        AdjustStockRequest negReq = new AdjustStockRequest(productId, warehouseId, -5L, "Damaged water leak in warehouse aisle 3", null);
        inventoryService.adjustStock(orgId, actorUserId, negReq, null);

        InventoryBalance bal2 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productId, warehouseId).orElseThrow();
        assertThat(bal2.getOnHand()).isEqualTo(35L);

        // Negative adjustment that would result in negative on-hand is rejected
        AdjustStockRequest excessiveNeg = new AdjustStockRequest(productId, warehouseId, -40L, "Excessive reduction", null);
        assertThatThrownBy(() -> inventoryService.adjustStock(orgId, actorUserId, excessiveNeg, null))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("negative on-hand inventory");

        // Verify balance was untouched by failed adjustment
        InventoryBalance bal3 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productId, warehouseId).orElseThrow();
        assertThat(bal3.getOnHand()).isEqualTo(35L);
    }

    @Test
    @DisplayName("Stock adjustment is rejected if reducing on-hand below existing reserved quantity")
    void adjustStockRejectedBelowReserved() {
        // Set on_hand = 20, reserved = 15 directly to simulate reserved order holds
        jdbcTemplate.update("""
                INSERT INTO inventory_balances (id, organization_id, product_id, warehouse_id, on_hand, reserved, updated_at)
                VALUES (gen_random_uuid(), ?, ?, ?, 20, 15, NOW())
                ON CONFLICT (organization_id, product_id, warehouse_id) DO UPDATE
                SET on_hand = 20, reserved = 15;
                """, orgId, productId, warehouseId);

        // Reduction of 10 would leave on_hand = 10, which is strictly < reserved (15)
        AdjustStockRequest req = new AdjustStockRequest(productId, warehouseId, -10L, "Reduction below hold", null);
        assertThatThrownBy(() -> inventoryService.adjustStock(orgId, actorUserId, req, null))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining("below current reserved holds");

        // Reduction of 5 leaves on_hand = 15 == reserved (15), which is permitted
        AdjustStockRequest validReq = new AdjustStockRequest(productId, warehouseId, -5L, "Reduction to reserved hold boundary", null);
        inventoryService.adjustStock(orgId, actorUserId, validReq, null);

        InventoryBalance balance = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productId, warehouseId).orElseThrow();
        assertThat(balance.getOnHand()).isEqualTo(15L);
        assertThat(balance.getReserved()).isEqualTo(15L);
        assertThat(balance.getAvailable()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Database immutability trigger prevents UPDATE or DELETE on inventory_ledger_entries")
    void ledgerImmutabilityEnforcedByDatabaseTrigger() {
        ReceiveStockRequest req = new ReceiveStockRequest(productId, warehouseId, 20L, null, "PO-1");
        InventoryTransactionResponse tx = inventoryService.receiveStock(orgId, actorUserId, req, null);
        UUID ledgerEntryId = tx.entries().get(0).id();

        // Attempting direct UPDATE on ledger table fails at DB trigger level
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE inventory_ledger_entries SET on_hand_delta = 100 WHERE id = ?", ledgerEntryId
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        // Attempting direct DELETE on ledger table fails at DB trigger level
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM inventory_ledger_entries WHERE id = ?", ledgerEntryId
        )).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("Second opening balance is rejected even after stock movements deplete on-hand to zero (leaves DB state untouched)")
    void secondOpeningBalanceRejectedAfterDepletionToZero() {
        // 1. Initial opening balance of 10 units
        OpeningBalanceRequest openReq = new OpeningBalanceRequest(productId, warehouseId, 10L, "Initial 10 units");
        inventoryService.recordOpeningBalance(orgId, actorUserId, openReq, null);

        InventoryBalance bal1 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productId, warehouseId).orElseThrow();
        assertThat(bal1.getOnHand()).isEqualTo(10L);

        // 2. Deplete on-hand to zero via valid adjustment of -10 units
        AdjustStockRequest depleteReq = new AdjustStockRequest(productId, warehouseId, -10L, "Deplete completely to zero", null);
        inventoryService.adjustStock(orgId, actorUserId, depleteReq, null);

        InventoryBalance bal2 = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productId, warehouseId).orElseThrow();
        assertThat(bal2.getOnHand()).isEqualTo(0L);
        assertThat(bal2.getReserved()).isEqualTo(0L);

        // Capture snapshot counts before attempted second opening balance
        int txCountBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_transactions WHERE organization_id = ?", Integer.class, orgId
        );
        int ledgerCountBefore = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger_entries WHERE organization_id = ?", Integer.class, orgId
        );

        // 3. Attempt second opening balance: MUST be rejected because an OPENING_BALANCE historically occurred
        OpeningBalanceRequest secondOpenReq = new OpeningBalanceRequest(productId, warehouseId, 25L, "Second opening attempt");
        assertThatThrownBy(() -> inventoryService.recordOpeningBalance(orgId, actorUserId, secondOpenReq, null))
                .isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Opening balance has already been established");

        // 4. Verify rejection leaves balance, transaction count, and ledger count unchanged
        InventoryBalance balAfter = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productId, warehouseId).orElseThrow();
        assertThat(balAfter.getOnHand()).isEqualTo(0L);
        assertThat(balAfter.getReserved()).isEqualTo(0L);

        int txCountAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_transactions WHERE organization_id = ?", Integer.class, orgId
        );
        int ledgerCountAfter = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM inventory_ledger_entries WHERE organization_id = ?", Integer.class, orgId
        );
        assertThat(txCountAfter).isEqualTo(txCountBefore);
        assertThat(ledgerCountAfter).isEqualTo(ledgerCountBefore);
    }

    @Test
    @DisplayName("Historical opening balance is detected even if the balance row is re-initialized")
    void historicalOpeningBalanceDetectedEvenIfBalanceRowReinitialized() {
        // Initial opening balance of 15
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productId, warehouseId, 15L, "Opening"), null);

        // Even if on_hand and reserved are reset to 0 in inventory_balances table
        jdbcTemplate.update("""
                UPDATE inventory_balances
                SET on_hand = 0, reserved = 0
                WHERE organization_id = ? AND product_id = ? AND warehouse_id = ?
                """, orgId, productId, warehouseId);

        // Historical transaction/ledger check detects prior opening balance and forbids another
        assertThatThrownBy(() -> inventoryService.recordOpeningBalance(
                orgId, actorUserId, new OpeningBalanceRequest(productId, warehouseId, 20L, "Re-open"), null
        )).isInstanceOf(InvalidOperationException.class)
                .hasMessageContaining("Opening balance has already been established");
    }
}
