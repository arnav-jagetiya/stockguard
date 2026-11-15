package com.stockguard.dashboard;

import com.stockguard.inventory.domain.DiscrepancyStatus;
import com.stockguard.inventory.domain.DiscrepancyType;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.security.domain.AuthSession;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.AuthSessionRepository;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.security.service.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class DashboardApiIntegrationTest {

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
    private WebApplicationContext context;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private AuthSessionRepository sessionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    private UUID orgAId;
    private UUID orgBId;
    private UUID adminUserId;
    private UUID viewerUserId;

    private String adminJwt;
    private String viewerJwt;

    private UUID productA1Id;
    private UUID productA2Id;
    private UUID productAInactiveId;
    private UUID warehouseA1Id;
    private UUID warehouseA2Id;
    private UUID warehouseAInactiveId;

    private UUID productB1Id;
    private UUID warehouseB1Id;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        // Organizations
        Organization orgA = organizationRepository.save(Organization.create("Dashboard Org A " + UUID.randomUUID()));
        orgAId = orgA.getId();

        Organization orgB = organizationRepository.save(Organization.create("Dashboard Org B " + UUID.randomUUID()));
        orgBId = orgB.getId();

        // Users & Roles
        User adminUser = userRepository.save(User.create("dash.admin." + UUID.randomUUID() + "@stockguard.com", "hash"));
        adminUserId = adminUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgAId, adminUserId, Role.ADMIN));
        AuthSession adminSession = sessionRepository.save(AuthSession.create(adminUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        adminJwt = jwtService.generateAccessToken(adminUserId, adminUser.getEmail(), adminSession.getId());

        User viewerUser = userRepository.save(User.create("dash.viewer." + UUID.randomUUID() + "@stockguard.com", "hash"));
        viewerUserId = viewerUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgAId, viewerUserId, Role.VIEWER));
        AuthSession viewerSession = sessionRepository.save(AuthSession.create(viewerUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        viewerJwt = jwtService.generateAccessToken(viewerUserId, viewerUser.getEmail(), viewerSession.getId());

        // Products in Org A: 2 active, 1 inactive
        productA1Id = UUID.randomUUID();
        productA2Id = UUID.randomUUID();
        productAInactiveId = UUID.randomUUID();
        String skuD1 = "SKU-D1-" + UUID.randomUUID().toString().substring(0, 8);
        String skuD2 = "SKU-D2-" + UUID.randomUUID().toString().substring(0, 8);
        String skuDInact = "SKU-DINACT-" + UUID.randomUUID().toString().substring(0, 8);

        jdbcTemplate.update("INSERT INTO products (id, organization_id, sku, normalized_sku, name, active) VALUES (?, ?, ?, ?, ?, true)",
                productA1Id, orgAId, skuD1, skuD1.toUpperCase(), "Product D1");
        jdbcTemplate.update("INSERT INTO products (id, organization_id, sku, normalized_sku, name, active) VALUES (?, ?, ?, ?, ?, true)",
                productA2Id, orgAId, skuD2, skuD2.toUpperCase(), "Product D2");
        jdbcTemplate.update("INSERT INTO products (id, organization_id, sku, normalized_sku, name, active) VALUES (?, ?, ?, ?, ?, false)",
                productAInactiveId, orgAId, skuDInact, skuDInact.toUpperCase(), "Product Inactive");

        // Warehouses in Org A: 2 active, 1 inactive
        warehouseA1Id = UUID.randomUUID();
        warehouseA2Id = UUID.randomUUID();
        warehouseAInactiveId = UUID.randomUUID();
        String whDA1 = "WH-DA1-" + UUID.randomUUID().toString().substring(0, 6);
        String whDA2 = "WH-DA2-" + UUID.randomUUID().toString().substring(0, 6);
        String whInact = "WH-INACT-" + UUID.randomUUID().toString().substring(0, 6);

        jdbcTemplate.update("INSERT INTO warehouses (id, organization_id, code, normalized_code, name, priority, active) VALUES (?, ?, ?, ?, ?, 1, true)",
                warehouseA1Id, orgAId, whDA1, whDA1.toUpperCase(), "Warehouse DA1");
        jdbcTemplate.update("INSERT INTO warehouses (id, organization_id, code, normalized_code, name, priority, active) VALUES (?, ?, ?, ?, ?, 2, true)",
                warehouseA2Id, orgAId, whDA2, whDA2.toUpperCase(), "Warehouse DA2");
        jdbcTemplate.update("INSERT INTO warehouses (id, organization_id, code, normalized_code, name, priority, active) VALUES (?, ?, ?, ?, ?, 3, false)",
                warehouseAInactiveId, orgAId, whInact, whInact.toUpperCase(), "Warehouse Inactive");

        // Products and Warehouses in Org B
        productB1Id = UUID.randomUUID();
        warehouseB1Id = UUID.randomUUID();
        String skuDB1 = "SKU-DB1-" + UUID.randomUUID().toString().substring(0, 8);
        String whDB1 = "WH-DB1-" + UUID.randomUUID().toString().substring(0, 6);

        jdbcTemplate.update("INSERT INTO products (id, organization_id, sku, normalized_sku, name, active) VALUES (?, ?, ?, ?, ?, true)",
                productB1Id, orgBId, skuDB1, skuDB1.toUpperCase(), "Product B1");
        jdbcTemplate.update("INSERT INTO warehouses (id, organization_id, code, normalized_code, name, priority, active) VALUES (?, ?, ?, ?, ?, 1, true)",
                warehouseB1Id, orgBId, whDB1, whDB1.toUpperCase(), "Warehouse B1");

        // Inventory Balances in Org A
        // Product A1 in WH A1: 100 on_hand, 20 reserved (available 80)
        jdbcTemplate.update("INSERT INTO inventory_balances (id, organization_id, product_id, warehouse_id, on_hand, reserved, updated_at) VALUES (?, ?, ?, ?, 100, 20, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), orgAId, productA1Id, warehouseA1Id);
        // Product A1 in WH A2: 50 on_hand, 10 reserved (available 40)
        jdbcTemplate.update("INSERT INTO inventory_balances (id, organization_id, product_id, warehouse_id, on_hand, reserved, updated_at) VALUES (?, ?, ?, ?, 50, 10, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), orgAId, productA1Id, warehouseA2Id);
        // Product A2 has no balance rows -> out of stock product!
        // Total on_hand = 150, reserved = 30, available = 120, out_of_stock = 1 (Product A2)

        // Inventory Balances in Org B (Tenant isolation)
        jdbcTemplate.update("INSERT INTO inventory_balances (id, organization_id, product_id, warehouse_id, on_hand, reserved, updated_at) VALUES (?, ?, ?, ?, 999, 99, CURRENT_TIMESTAMP)",
                UUID.randomUUID(), orgBId, productB1Id, warehouseB1Id);

        // Orders & Reservations in Org A
        seedOrdersAndReservations(orgAId, adminUserId, productA1Id, warehouseA1Id);

        // Discrepancy in Org A
        jdbcTemplate.update("""
            INSERT INTO reconciliation_discrepancies (
                id, organization_id, product_id, warehouse_id, discrepancy_type, status,
                observed_balance_on_hand, observed_ledger_on_hand,
                observed_balance_reserved, observed_ledger_reserved,
                consecutive_detection_count, first_detected_at, last_detected_at, version
            ) VALUES (?, ?, ?, ?, 'DELTA_MISMATCH', 'OPEN', 100, 90, 0, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)
            """, UUID.randomUUID(), orgAId, productA1Id, warehouseA1Id);

        // Stock movement in Org A
        UUID txId = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO inventory_transactions (id, organization_id, transaction_type, reference_type, reference_id, notes, actor_user_id, created_at)
            VALUES (?, ?, 'RECEIPT', NULL, NULL, 'Initial stock receipt', ?, CURRENT_TIMESTAMP)
            """, txId, orgAId, adminUserId);
        jdbcTemplate.update("""
            INSERT INTO inventory_ledger_entries (id, organization_id, transaction_id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, created_at)
            VALUES (?, ?, ?, 1, ?, ?, 100, 0, CURRENT_TIMESTAMP)
            """, UUID.randomUUID(), orgAId, txId, productA1Id, warehouseA1Id);
    }

    private void seedOrdersAndReservations(UUID orgId, UUID userId, UUID prodId, UUID whId) {
        // Orders: PENDING, CONFIRMED, FULFILLED, CANCELLED
        UUID order1 = UUID.randomUUID();
        UUID order2 = UUID.randomUUID();
        UUID order3 = UUID.randomUUID();
        UUID order4 = UUID.randomUUID();

        jdbcTemplate.update("INSERT INTO orders (id, organization_id, order_number, status, total_amount, currency) VALUES (?, ?, ?, 'PENDING', 50.00, 'USD')",
                order1, orgId, "ORD-P-" + UUID.randomUUID().toString().substring(0, 6));
        jdbcTemplate.update("INSERT INTO orders (id, organization_id, order_number, status, total_amount, currency) VALUES (?, ?, ?, 'CONFIRMED', 100.00, 'USD')",
                order2, orgId, "ORD-C-" + UUID.randomUUID().toString().substring(0, 6));
        jdbcTemplate.update("INSERT INTO orders (id, organization_id, order_number, status, total_amount, currency) VALUES (?, ?, ?, 'FULFILLED', 150.00, 'USD')",
                order3, orgId, "ORD-F-" + UUID.randomUUID().toString().substring(0, 6));
        jdbcTemplate.update("INSERT INTO orders (id, organization_id, order_number, status, total_amount, currency) VALUES (?, ?, ?, 'CANCELLED', 20.00, 'USD')",
                order4, orgId, "ORD-X-" + UUID.randomUUID().toString().substring(0, 6));

        // Order items
        UUID item1 = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO order_items (id, organization_id, order_id, line_number, product_id, sku_snapshot, name_snapshot, quantity, unit_price) VALUES (?, ?, ?, 1, ?, 'SKU-D1', 'Product D1', 5, 10.00)",
                item1, orgId, order2, prodId);

        // Active reservation for order 2
        UUID resId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO reservations (id, organization_id, order_id, status, expires_at) VALUES (?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP + INTERVAL '15 minutes')",
                resId, orgId, order2);

        // Reservation allocation
        jdbcTemplate.update("INSERT INTO reservation_allocations (id, organization_id, reservation_id, order_id, order_item_id, product_id, warehouse_id, quantity) VALUES (?, ?, ?, ?, ?, ?, ?, 5)",
                UUID.randomUUID(), orgId, resId, order2, item1, prodId, whId);
    }

    // =========================================================================
    // 1. DASHBOARD SUMMARY ENDPOINT
    // =========================================================================

    @Test
    @DisplayName("GET /api/dashboard/summary returns point-in-time consistent metrics for authorized ADMIN")
    void getDashboardSummary_Admin_Success() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(orgAId.toString()))
                .andExpect(jsonPath("$.generatedAt").isNotEmpty())
                .andExpect(jsonPath("$.catalog.activeProductsCount").value(2))
                .andExpect(jsonPath("$.catalog.activeWarehousesCount").value(2))
                .andExpect(jsonPath("$.inventory.totalOnHand").value(150))
                .andExpect(jsonPath("$.inventory.totalReserved").value(30))
                .andExpect(jsonPath("$.inventory.totalAvailable").value(120))
                .andExpect(jsonPath("$.inventory.outOfStockActiveProductsCount").value(1))
                .andExpect(jsonPath("$.reservations.activeReservationsCount").value(1))
                .andExpect(jsonPath("$.reservations.totalReservedUnits").value(5))
                .andExpect(jsonPath("$.orders.pendingCount").value(1))
                .andExpect(jsonPath("$.orders.confirmedCount").value(1))
                .andExpect(jsonPath("$.orders.fulfilledCount").value(1))
                .andExpect(jsonPath("$.orders.cancelledCount").value(1))
                .andExpect(jsonPath("$.integrity.openDiscrepanciesCount").value(1))
                .andExpect(jsonPath("$.recentMovements", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.recentMovements[0].transactionType").value("RECEIPT"));
    }

    @Test
    @DisplayName("GET /api/dashboard/summary is accessible to VIEWER role having dashboard:view permission")
    void getDashboardSummary_Viewer_Success() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary")
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(orgAId.toString()))
                .andExpect(jsonPath("$.catalog.activeProductsCount").value(2));
    }

    @Test
    @DisplayName("GET /api/dashboard/summary enforces tenant isolation (zero bleed from Org B)")
    void getDashboardSummary_TenantIsolation() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isOk())
                // Org B has on_hand 999; Org A must have strictly 150
                .andExpect(jsonPath("$.inventory.totalOnHand").value(150));
    }

    @Test
    @DisplayName("GET /api/dashboard/summary fails with 400 when X-Organization-Id header is missing")
    void getDashboardSummary_MissingOrgHeader_Returns400() throws Exception {
        mockMvc.perform(get("/api/dashboard/summary")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // 2. DASHBOARD WAREHOUSE DRILLDOWN ENDPOINT
    // =========================================================================

    @Test
    @DisplayName("GET /api/dashboard/warehouses/{id} returns drilldown metrics for warehouse A1")
    void getWarehouseDashboard_Success() throws Exception {
        mockMvc.perform(get("/api/dashboard/warehouses/{id}", warehouseA1Id)
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(orgAId.toString()))
                .andExpect(jsonPath("$.warehouseId").value(warehouseA1Id.toString()))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.inventory.totalOnHand").value(100))
                .andExpect(jsonPath("$.inventory.totalReserved").value(20))
                .andExpect(jsonPath("$.inventory.totalAvailable").value(80))
                .andExpect(jsonPath("$.inventory.outOfStockProductsCount").value(1))
                .andExpect(jsonPath("$.reservations.activeAllocationsCount").value(1))
                .andExpect(jsonPath("$.reservations.totalAllocatedUnits").value(5))
                .andExpect(jsonPath("$.integrity.openDiscrepanciesCount").value(1))
                .andExpect(jsonPath("$.recentMovements", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("GET /api/dashboard/warehouses/{id} returns 404 for warehouse belonging to another tenant")
    void getWarehouseDashboard_CrossTenant_Returns404() throws Exception {
        mockMvc.perform(get("/api/dashboard/warehouses/{id}", warehouseB1Id)
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /api/dashboard/warehouses/{id} returns 404 for nonexistent warehouse")
    void getWarehouseDashboard_Nonexistent_Returns404() throws Exception {
        mockMvc.perform(get("/api/dashboard/warehouses/{id}", UUID.randomUUID())
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isNotFound());
    }
}
