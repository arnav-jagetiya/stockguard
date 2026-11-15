package com.stockguard.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockguard.inventory.domain.DiscrepancyStatus;
import com.stockguard.inventory.domain.DiscrepancyType;
import com.stockguard.inventory.dto.UpdateDiscrepancyRequest;
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
import org.springframework.http.MediaType;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class DiscrepancyApiIntegrationTest {

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
    private final ObjectMapper objectMapper = new ObjectMapper();

    private UUID orgAId;
    private UUID orgBId;
    private UUID adminUserId;
    private UUID managerUserId;
    private UUID operatorUserId;
    private UUID inactiveUserId;

    private String adminJwt;
    private String managerJwt;
    private String operatorJwt;

    private UUID productA1Id;
    private UUID productA2Id;
    private UUID warehouseA1Id;

    private UUID productB1Id;
    private UUID warehouseB1Id;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        // 1. Create Organization A
        Organization orgA = organizationRepository.save(Organization.create("Discrepancy Org A " + UUID.randomUUID()));
        orgAId = orgA.getId();

        // 2. Create Organization B (Tenant Isolation)
        Organization orgB = organizationRepository.save(Organization.create("Discrepancy Org B " + UUID.randomUUID()));
        orgBId = orgB.getId();

        // 3. Create Users
        User adminUser = userRepository.save(User.create("admin." + UUID.randomUUID() + "@stockguard.com", "hash"));
        adminUserId = adminUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgAId, adminUserId, Role.ADMIN));
        AuthSession adminSession = sessionRepository.save(AuthSession.create(adminUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        adminJwt = jwtService.generateAccessToken(adminUserId, adminUser.getEmail(), adminSession.getId());

        User managerUser = userRepository.save(User.create("mgr." + UUID.randomUUID() + "@stockguard.com", "hash"));
        managerUserId = managerUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgAId, managerUserId, Role.MANAGER));
        AuthSession managerSession = sessionRepository.save(AuthSession.create(managerUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        managerJwt = jwtService.generateAccessToken(managerUserId, managerUser.getEmail(), managerSession.getId());

        User operatorUser = userRepository.save(User.create("op." + UUID.randomUUID() + "@stockguard.com", "hash"));
        operatorUserId = operatorUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgAId, operatorUserId, Role.OPERATOR));
        AuthSession operatorSession = sessionRepository.save(AuthSession.create(operatorUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        operatorJwt = jwtService.generateAccessToken(operatorUserId, operatorUser.getEmail(), operatorSession.getId());

        User inactiveUser = userRepository.save(User.create("inactive." + UUID.randomUUID() + "@stockguard.com", "hash"));
        inactiveUserId = inactiveUser.getId();
        OrganizationMembership inactiveMem = OrganizationMembership.create(orgAId, inactiveUserId, Role.MANAGER);
        inactiveMem.setActive(false);
        membershipRepository.save(inactiveMem);

        // 4. Products and Warehouses
        productA1Id = UUID.randomUUID();
        productA2Id = UUID.randomUUID();
        warehouseA1Id = UUID.randomUUID();

        String skuA1 = "SKU-A1-" + UUID.randomUUID().toString().substring(0, 8);
        String skuA2 = "SKU-A2-" + UUID.randomUUID().toString().substring(0, 8);
        String whA1 = "WH-A1-" + UUID.randomUUID().toString().substring(0, 6);

        jdbcTemplate.update("INSERT INTO products (id, organization_id, sku, normalized_sku, name, active) VALUES (?, ?, ?, ?, ?, true)",
                productA1Id, orgAId, skuA1, skuA1.toUpperCase(), "Product A1");
        jdbcTemplate.update("INSERT INTO products (id, organization_id, sku, normalized_sku, name, active) VALUES (?, ?, ?, ?, ?, true)",
                productA2Id, orgAId, skuA2, skuA2.toUpperCase(), "Product A2");
        jdbcTemplate.update("INSERT INTO warehouses (id, organization_id, code, normalized_code, name, priority, active) VALUES (?, ?, ?, ?, ?, 1, true)",
                warehouseA1Id, orgAId, whA1, whA1.toUpperCase(), "Warehouse A1");

        productB1Id = UUID.randomUUID();
        warehouseB1Id = UUID.randomUUID();
        String skuB1 = "SKU-B1-" + UUID.randomUUID().toString().substring(0, 8);
        String whB1 = "WH-B1-" + UUID.randomUUID().toString().substring(0, 6);

        jdbcTemplate.update("INSERT INTO products (id, organization_id, sku, normalized_sku, name, active) VALUES (?, ?, ?, ?, ?, true)",
                productB1Id, orgBId, skuB1, skuB1.toUpperCase(), "Product B1");
        jdbcTemplate.update("INSERT INTO warehouses (id, organization_id, code, normalized_code, name, priority, active) VALUES (?, ?, ?, ?, ?, 1, true)",
                warehouseB1Id, orgBId, whB1, whB1.toUpperCase(), "Warehouse B1");
    }

    private UUID insertDiscrepancy(UUID orgId, UUID prodId, UUID whId, DiscrepancyType type, DiscrepancyStatus status) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
            INSERT INTO reconciliation_discrepancies (
                id, organization_id, product_id, warehouse_id, discrepancy_type, status,
                observed_balance_on_hand, observed_ledger_on_hand,
                observed_balance_reserved, observed_ledger_reserved,
                consecutive_detection_count, first_detected_at, last_detected_at, version
            ) VALUES (?, ?, ?, ?, ?, ?, 100, 90, 0, 0, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1)
            """, id, orgId, prodId, whId, type.name(), status.name());
        return id;
    }

    // =========================================================================
    // 1. RECONCILIATION TRIGGER ENDPOINT
    // =========================================================================

    @Test
    @DisplayName("POST /api/inventory/reconciliation/trigger executes sweep and returns 200 OK")
    void triggerReconciliation_Authorized_Returns200() throws Exception {
        mockMvc.perform(post("/api/inventory/reconciliation/trigger")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(orgAId.toString()))
                .andExpect(jsonPath("$.reconciledAt").isNotEmpty())
                .andExpect(jsonPath("$.discrepancies").isArray());
    }

    @Test
    @DisplayName("POST /api/inventory/reconciliation/trigger fails with 403 when user lacks inventory:reconcile")
    void triggerReconciliation_Unauthorized_Returns403() throws Exception {
        mockMvc.perform(post("/api/inventory/reconciliation/trigger")
                        .header("Authorization", "Bearer " + operatorJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/inventory/reconciliation/trigger fails with 400 when X-Organization-Id is missing")
    void triggerReconciliation_MissingOrgHeader_Returns400() throws Exception {
        mockMvc.perform(post("/api/inventory/reconciliation/trigger")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isBadRequest());
    }

    // =========================================================================
    // 2. DISCREPANCY LISTING & FILTERS
    // =========================================================================

    @Test
    @DisplayName("GET /api/inventory/reconciliation/discrepancies lists discrepancies filtered by status and paginated")
    void listDiscrepancies_FilterByStatusAndPagination_ReturnsPage() throws Exception {
        UUID d1 = insertDiscrepancy(orgAId, productA1Id, warehouseA1Id, DiscrepancyType.DELTA_MISMATCH, DiscrepancyStatus.OPEN);
        UUID d2 = insertDiscrepancy(orgAId, productA2Id, warehouseA1Id, DiscrepancyType.DELTA_MISMATCH, DiscrepancyStatus.ACKNOWLEDGED);

        mockMvc.perform(get("/api/inventory/reconciliation/discrepancies")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .param("status", "OPEN")
                        .param("limit", "10")
                        .param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(d1.toString()))
                .andExpect(jsonPath("$.items[0].status").value("OPEN"));
    }

    @Test
    @DisplayName("GET /api/inventory/reconciliation/discrepancies enforces strict tenant boundary isolation")
    void listDiscrepancies_TenantIsolation() throws Exception {
        UUID dOrgB = insertDiscrepancy(orgBId, productB1Id, warehouseB1Id, DiscrepancyType.DELTA_MISMATCH, DiscrepancyStatus.OPEN);

        mockMvc.perform(get("/api/inventory/reconciliation/discrepancies")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(0))
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    // =========================================================================
    // 3. GET SINGLE DISCREPANCY
    // =========================================================================

    @Test
    @DisplayName("GET /api/inventory/reconciliation/discrepancies/{id} returns discrepancy successfully")
    void getDiscrepancyById_Success() throws Exception {
        UUID dId = insertDiscrepancy(orgAId, productA1Id, warehouseA1Id, DiscrepancyType.DELTA_MISMATCH, DiscrepancyStatus.OPEN);

        mockMvc.perform(get("/api/inventory/reconciliation/discrepancies/{id}", dId)
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(dId.toString()))
                .andExpect(jsonPath("$.productId").value(productA1Id.toString()))
                .andExpect(jsonPath("$.warehouseId").value(warehouseA1Id.toString()))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    @DisplayName("GET /api/inventory/reconciliation/discrepancies/{id} returns 404 for cross-tenant access")
    void getDiscrepancyById_CrossTenant_Returns404() throws Exception {
        UUID dOrgB = insertDiscrepancy(orgBId, productB1Id, warehouseB1Id, DiscrepancyType.DELTA_MISMATCH, DiscrepancyStatus.OPEN);

        mockMvc.perform(get("/api/inventory/reconciliation/discrepancies/{id}", dOrgB)
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isNotFound());
    }

    // =========================================================================
    // 4. LIFECYCLE MUTATIONS & ASSIGNMENT
    // =========================================================================

    @Test
    @DisplayName("PATCH transitions OPEN to ACKNOWLEDGED successfully and increments version")
    void updateDiscrepancy_Acknowledge_Success() throws Exception {
        UUID dId = insertDiscrepancy(orgAId, productA1Id, warehouseA1Id, DiscrepancyType.DELTA_MISMATCH, DiscrepancyStatus.OPEN);

        UpdateDiscrepancyRequest req = new UpdateDiscrepancyRequest(
                DiscrepancyStatus.ACKNOWLEDGED,
                null,
                "Operator acknowledged this discrepancy",
                null,
                1
        );

        mockMvc.perform(patch("/api/inventory/reconciliation/discrepancies/{id}", dId)
                        .header("Authorization", "Bearer " + managerJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.investigationNotes").value("Operator acknowledged this discrepancy"));
    }

    @Test
    @DisplayName("PATCH transitions to INVESTIGATING with valid assignee successfully")
    void updateDiscrepancy_AssignToActiveManager_Success() throws Exception {
        UUID dId = insertDiscrepancy(orgAId, productA1Id, warehouseA1Id, DiscrepancyType.DELTA_MISMATCH, DiscrepancyStatus.OPEN);

        UpdateDiscrepancyRequest req = new UpdateDiscrepancyRequest(
                DiscrepancyStatus.INVESTIGATING,
                managerUserId,
                "Assigned to manager for physical count verification",
                null,
                1
        );

        mockMvc.perform(patch("/api/inventory/reconciliation/discrepancies/{id}", dId)
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INVESTIGATING"))
                .andExpect(jsonPath("$.assignedToUserId").value(managerUserId.toString()))
                .andExpect(jsonPath("$.assignedToUserActive").value(true))
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    @DisplayName("PATCH fails with 400 when assigning to inactive organization member")
    void updateDiscrepancy_AssignToInactiveUser_Fails() throws Exception {
        UUID dId = insertDiscrepancy(orgAId, productA1Id, warehouseA1Id, DiscrepancyType.DELTA_MISMATCH, DiscrepancyStatus.OPEN);

        UpdateDiscrepancyRequest req = new UpdateDiscrepancyRequest(
                DiscrepancyStatus.INVESTIGATING,
                inactiveUserId,
                "Attempting to assign to inactive user",
                null,
                1
        );

        mockMvc.perform(patch("/api/inventory/reconciliation/discrepancies/{id}", dId)
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH fails with 400 when assigning to user without inventory:reconcile authority")
    void updateDiscrepancy_AssignToOperatorWithoutReconcile_Fails() throws Exception {
        UUID dId = insertDiscrepancy(orgAId, productA1Id, warehouseA1Id, DiscrepancyType.DELTA_MISMATCH, DiscrepancyStatus.OPEN);

        UpdateDiscrepancyRequest req = new UpdateDiscrepancyRequest(
                DiscrepancyStatus.INVESTIGATING,
                operatorUserId,
                "Attempting to assign to operator",
                null,
                1
        );

        mockMvc.perform(patch("/api/inventory/reconciliation/discrepancies/{id}", dId)
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH fails with 400 when resolving with empty or whitespace-only reason")
    void updateDiscrepancy_ResolveWithBlankReason_Fails() throws Exception {
        UUID dId = insertDiscrepancy(orgAId, productA1Id, warehouseA1Id, DiscrepancyType.DELTA_MISMATCH, DiscrepancyStatus.OPEN);

        UpdateDiscrepancyRequest req = new UpdateDiscrepancyRequest(
                DiscrepancyStatus.RESOLVED,
                null,
                null,
                "   \t\n  ",
                1
        );

        mockMvc.perform(patch("/api/inventory/reconciliation/discrepancies/{id}", dId)
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH resolves discrepancy successfully with HUMAN attribution and resolution reason")
    void updateDiscrepancy_ResolveValid_SetsHumanAttribution() throws Exception {
        UUID dId = insertDiscrepancy(orgAId, productA1Id, warehouseA1Id, DiscrepancyType.DELTA_MISMATCH, DiscrepancyStatus.OPEN);

        UpdateDiscrepancyRequest req = new UpdateDiscrepancyRequest(
                DiscrepancyStatus.RESOLVED,
                null,
                "Physical count reconciled",
                "Count verified against physical stock count sheet 401",
                1
        );

        mockMvc.perform(patch("/api/inventory/reconciliation/discrepancies/{id}", dId)
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolutionSource").value("HUMAN"))
                .andExpect(jsonPath("$.resolvedByUserId").value(adminUserId.toString()))
                .andExpect(jsonPath("$.resolutionReason").value("Count verified against physical stock count sheet 401"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    @DisplayName("PATCH rejects discrepancy successfully with HUMAN attribution and non-blank reason")
    void updateDiscrepancy_RejectValid_SetsHumanAttribution() throws Exception {
        UUID dId = insertDiscrepancy(orgAId, productA1Id, warehouseA1Id, DiscrepancyType.DELTA_MISMATCH, DiscrepancyStatus.OPEN);

        UpdateDiscrepancyRequest req = new UpdateDiscrepancyRequest(
                DiscrepancyStatus.REJECTED,
                null,
                "False positive investigation complete",
                "Hardware sensor glitch confirmed; ledger entry valid",
                1
        );

        mockMvc.perform(patch("/api/inventory/reconciliation/discrepancies/{id}", dId)
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.resolutionSource").value("HUMAN"))
                .andExpect(jsonPath("$.resolvedByUserId").value(adminUserId.toString()))
                .andExpect(jsonPath("$.resolutionReason").value("Hardware sensor glitch confirmed; ledger entry valid"))
                .andExpect(jsonPath("$.resolvedAt").isNotEmpty())
                .andExpect(jsonPath("$.version").value(2));
    }

    @Test
    @DisplayName("PATCH fails with 400 when attempting to update a terminal RESOLVED discrepancy")
    void updateDiscrepancy_TerminalStateImmutability_Fails() throws Exception {
        UUID dId = insertDiscrepancy(orgAId, productA1Id, warehouseA1Id, DiscrepancyType.DELTA_MISMATCH, DiscrepancyStatus.OPEN);

        // First resolve it
        UpdateDiscrepancyRequest resolveReq = new UpdateDiscrepancyRequest(
                DiscrepancyStatus.RESOLVED,
                null,
                "Done",
                "Stock aligned",
                1
        );
        mockMvc.perform(patch("/api/inventory/reconciliation/discrepancies/{id}", dId)
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resolveReq)))
                .andExpect(status().isOk());

        // Now attempt to reopen or update notes on the resolved discrepancy
        UpdateDiscrepancyRequest updateReq = new UpdateDiscrepancyRequest(
                DiscrepancyStatus.OPEN,
                null,
                "Attempt to reopen",
                null,
                2
        );
        mockMvc.perform(patch("/api/inventory/reconciliation/discrepancies/{id}", dId)
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH fails with 409 Conflict on optimistic locking version mismatch")
    void updateDiscrepancy_OptimisticLockFailure_Returns409() throws Exception {
        UUID dId = insertDiscrepancy(orgAId, productA1Id, warehouseA1Id, DiscrepancyType.DELTA_MISMATCH, DiscrepancyStatus.OPEN);

        // Advance row version to 2
        jdbcTemplate.update("UPDATE reconciliation_discrepancies SET version = 2 WHERE id = ?", dId);

        // Submit update expecting version 1
        UpdateDiscrepancyRequest req = new UpdateDiscrepancyRequest(
                DiscrepancyStatus.ACKNOWLEDGED,
                null,
                "Stale update attempt",
                null,
                1
        );

        mockMvc.perform(patch("/api/inventory/reconciliation/discrepancies/{id}", dId)
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict());
    }
}
