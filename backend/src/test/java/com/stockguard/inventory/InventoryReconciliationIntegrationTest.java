package com.stockguard.inventory;

import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.dto.OpeningBalanceRequest;
import com.stockguard.inventory.dto.ReceiveStockRequest;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class InventoryReconciliationIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    private UUID orgId;
    private UUID actorUserId;
    private UUID productId;
    private UUID warehouseId;

    @BeforeEach
    void setUp() {
        Organization org = organizationRepository.save(Organization.create("Reconciliation Org " + UUID.randomUUID()));
        orgId = org.getId();

        User user = User.create("recon.actor." + UUID.randomUUID() + "@stockguard.com", "hash");
        user = userRepository.save(user);
        actorUserId = user.getId();

        OrganizationMembership membership = OrganizationMembership.create(orgId, actorUserId, Role.OPERATOR);
        membershipRepository.save(membership);

        Product product = productRepository.save(new Product(orgId, "SKU-RECON-" + UUID.randomUUID().toString().substring(0, 8), "Recon Product", null));
        productId = product.getId();

        Warehouse warehouse = warehouseRepository.save(new Warehouse(orgId, "WH-RECON-" + UUID.randomUUID().toString().substring(0, 6), "Recon WH", "Loc", 1));
        warehouseId = warehouse.getId();
    }

    @Test
    @DisplayName("Mathematical reconciliation reports zero discrepancies when balances match ledger sum")
    void reconciliationReportsZeroDiscrepanciesWhenConsistent() {
        // Record opening balance of 100
        inventoryService.recordOpeningBalance(orgId, actorUserId, new OpeningBalanceRequest(productId, warehouseId, 100L, "Initial"), null);
        // Receive 50
        inventoryService.receiveStock(orgId, actorUserId, new ReceiveStockRequest(productId, warehouseId, 50L, null, "PO-1"), null);

        ReconciliationReport report = reconciliationService.reconcile(orgId);

        assertThat(report.totalBalancesInspected()).isGreaterThanOrEqualTo(1);
        assertThat(report.discrepantBalances()).isEqualTo(0);
        assertThat(report.discrepancies()).isEmpty();
    }

    @Test
    @DisplayName("Mathematical reconciliation detects delta mismatch when balance is tampered without ledger history")
    void reconciliationDetectsDeltaMismatchOnTamperedBalance() {
        // Record 50 units
        inventoryService.receiveStock(orgId, actorUserId, new ReceiveStockRequest(productId, warehouseId, 50L, null, "PO-1"), null);

        // Tamper directly with the balance row (simulate data corruption)
        jdbcTemplate.update(
                "UPDATE inventory_balances SET on_hand = 999 WHERE organization_id = ? AND product_id = ? AND warehouse_id = ?",
                orgId, productId, warehouseId
        );

        ReconciliationReport report = reconciliationService.reconcile(orgId);

        assertThat(report.discrepantBalances()).isEqualTo(1);
        assertThat(report.discrepancies()).hasSize(1);

        var discrepancy = report.discrepancies().get(0);
        assertThat(discrepancy.productId()).isEqualTo(productId);
        assertThat(discrepancy.warehouseId()).isEqualTo(warehouseId);
        assertThat(discrepancy.balanceOnHand()).isEqualTo(999L);
        assertThat(discrepancy.ledgerOnHandSum()).isEqualTo(50L);
        assertThat(discrepancy.discrepancyType()).isEqualTo("DELTA_MISMATCH");

        // Verify that reconciliation is strictly read-only and did not modify the balance
        InventoryBalance balanceAfterRecon = balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(orgId, productId, warehouseId).orElseThrow();
        assertThat(balanceAfterRecon.getOnHand()).isEqualTo(999L);
    }
}
