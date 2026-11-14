package com.stockguard.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockguard.audit.domain.AuditAction;
import com.stockguard.audit.domain.AuditEntityType;
import com.stockguard.audit.domain.AuditLog;
import com.stockguard.audit.service.AuditLogService;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.security.domain.AuthSession;
import com.stockguard.security.domain.User;
import com.stockguard.security.dto.LoginRequest;
import com.stockguard.security.dto.SwitchOrgRequest;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class AuditLogApiIntegrationTest {

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
    private AuditLogService auditLogService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private UUID orgAId;
    private UUID orgBId;
    private UUID adminUserId;
    private UUID managerUserId;
    private UUID operatorUserId;
    private UUID viewerUserId;

    private String adminJwt;
    private String managerJwt;
    private String operatorJwt;
    private String viewerJwt;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        // 1. Create Organization A
        Organization orgA = organizationRepository.save(Organization.create("Audit Org A " + UUID.randomUUID()));
        orgAId = orgA.getId();

        // 2. Create Organization B (for tenant isolation tests)
        Organization orgB = organizationRepository.save(Organization.create("Audit Org B " + UUID.randomUUID()));
        orgBId = orgB.getId();

        // 3. Create ADMIN user & session in Org A
        User adminUser = userRepository.save(User.create("admin." + UUID.randomUUID() + "@stockguard.com", "hash"));
        adminUserId = adminUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgAId, adminUserId, Role.ADMIN));
        AuthSession adminSession = sessionRepository.save(AuthSession.create(adminUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        adminJwt = jwtService.generateAccessToken(adminUserId, adminUser.getEmail(), adminSession.getId());

        // 4. Create MANAGER user & session in Org A
        User managerUser = userRepository.save(User.create("mgr." + UUID.randomUUID() + "@stockguard.com", "hash"));
        managerUserId = managerUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgAId, managerUserId, Role.MANAGER));
        AuthSession managerSession = sessionRepository.save(AuthSession.create(managerUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        managerJwt = jwtService.generateAccessToken(managerUserId, managerUser.getEmail(), managerSession.getId());

        // 5. Create OPERATOR user & session in Org A
        User operatorUser = userRepository.save(User.create("op." + UUID.randomUUID() + "@stockguard.com", "hash"));
        operatorUserId = operatorUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgAId, operatorUserId, Role.OPERATOR));
        AuthSession opSession = sessionRepository.save(AuthSession.create(operatorUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        operatorJwt = jwtService.generateAccessToken(operatorUserId, operatorUser.getEmail(), opSession.getId());

        // 6. Create VIEWER user & session in Org A
        User viewerUser = userRepository.save(User.create("viewer." + UUID.randomUUID() + "@stockguard.com", "hash"));
        viewerUserId = viewerUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgAId, viewerUserId, Role.VIEWER));
        AuthSession viewerSession = sessionRepository.save(AuthSession.create(viewerUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        viewerJwt = jwtService.generateAccessToken(viewerUserId, viewerUser.getEmail(), viewerSession.getId());
    }

    // =========================================================================
    // 1. AUTHENTICATION & HEADER VALIDATION
    // =========================================================================

    @Test
    @DisplayName("GET /api/audit-logs without JWT is rejected with 401 Unauthorized")
    void unauthenticatedRequestRejected() throws Exception {
        mockMvc.perform(get("/api/audit-logs")
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/audit-logs without X-Organization-Id header is rejected with 400 Bad Request")
    void missingOrganizationHeaderRejected() throws Exception {
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("X-Organization-Id")));
    }

    @Test
    @DisplayName("GET /api/audit-logs with malformed X-Organization-Id header is rejected with 400 Bad Request")
    void malformedOrganizationHeaderRejected() throws Exception {
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", "not-a-valid-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("format")));
    }

    @Test
    @DisplayName("GET /api/audit-logs with foreign organization where user holds no membership is rejected with 403 Forbidden")
    void foreignOrganizationMembershipRejected() throws Exception {
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgBId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    // =========================================================================
    // 2. AUTHORIZATION & RBAC (audit:view permission check)
    // =========================================================================

    @Test
    @DisplayName("OPERATOR role lacks audit:view and is rejected with 403 Forbidden")
    void operatorRoleForbidden() throws Exception {
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + operatorJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("VIEWER role lacks audit:view and is rejected with 403 Forbidden")
    void viewerRoleForbidden() throws Exception {
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("ADMIN role possesses audit:view and is allowed (200 OK)")
    void adminRoleAllowed() throws Exception {
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    @DisplayName("MANAGER role possesses audit:view and is allowed (200 OK)")
    void managerRoleAllowed() throws Exception {
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + managerJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    // =========================================================================
    // 3. TENANT ISOLATION
    // =========================================================================

    @Test
    @DisplayName("Tenant isolation: Org A query strictly returns Org A records and zero records from Org B")
    void tenantIsolationEnforced() throws Exception {
        // Create an admin user in Org B
        User orgBUser = userRepository.save(User.create("orgb.admin." + UUID.randomUUID() + "@stockguard.com", "hash"));
        membershipRepository.save(OrganizationMembership.create(orgBId, orgBUser.getId(), Role.ADMIN));

        // Insert audit records in Org A
        UUID productA = UUID.randomUUID();
        UUID warehouseA = UUID.randomUUID();
        auditLogService.logInventoryAdjustment(orgAId, adminUserId, productA, warehouseA, 10, "Org A adjustment", null);
        auditLogService.logOpeningBalance(orgAId, adminUserId, productA, warehouseA, 50, null);

        // Insert audit records in Org B
        UUID productB = UUID.randomUUID();
        UUID warehouseB = UUID.randomUUID();
        auditLogService.logInventoryAdjustment(orgBId, orgBUser.getId(), productB, warehouseB, 20, "Org B adjustment", null);

        // Query as Org A
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[*].organizationId", everyItem(equalTo(orgAId.toString()))));

        // Querying as Org A with Org B's product entity ID returns 0 elements
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .param("entityId", productB.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    // =========================================================================
    // 4. FILTERS & SEARCH
    // =========================================================================

    @Test
    @DisplayName("Filtering by action, entityType, entityId, and actorUserId")
    void filteringScenarios() throws Exception {
        UUID product1 = UUID.randomUUID();
        UUID warehouse1 = UUID.randomUUID();
        UUID order1 = UUID.randomUUID();

        // 1. Stock adjustment by admin
        auditLogService.logInventoryAdjustment(orgAId, adminUserId, product1, warehouse1, 10, "Adjustment 1", null);
        // 2. Opening balance by admin
        auditLogService.logOpeningBalance(orgAId, adminUserId, product1, warehouse1, 100, null);
        // 3. Order created by manager
        auditLogService.logOrderCreated(orgAId, managerUserId, order1, "ORD-1001", null, 1);

        // Filter by action = STOCK_ADJUSTED
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .param("action", AuditAction.STOCK_ADJUSTED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value(AuditAction.STOCK_ADJUSTED));

        // Filter by entityType = ORDER
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .param("entityType", AuditEntityType.ORDER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].entityType").value(AuditEntityType.ORDER))
                .andExpect(jsonPath("$.content[0].entityId").value(order1.toString()));

        // Filter by entityId = product1
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .param("entityId", product1.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].entityId", everyItem(equalTo(product1.toString()))));

        // Filter by actorUserId = managerUserId
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .param("actorUserId", managerUserId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].actorUserId").value(managerUserId.toString()));

        // Combined filter (action + entityType + actorUserId)
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .param("action", AuditAction.ORDER_CREATED)
                        .param("entityType", AuditEntityType.ORDER)
                        .param("actorUserId", managerUserId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].action").value(AuditAction.ORDER_CREATED));
    }

    // =========================================================================
    // 5. DATE RANGE FILTERING & VALIDATION
    // =========================================================================

    @Test
    @DisplayName("Valid date range filtering returns records in range")
    void dateRangeFiltering() throws Exception {
        UUID product = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();

        Instant before = Instant.now().minus(2, ChronoUnit.HOURS);
        auditLogService.logInventoryAdjustment(orgAId, adminUserId, product, warehouse, 5, "Past adjustment", null);
        Instant after = Instant.now().plus(2, ChronoUnit.HOURS);

        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .param("startDate", before.toString())
                        .param("endDate", after.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    @DisplayName("Invalid date range where startDate > endDate is rejected with 400 Bad Request")
    void invalidDateRangeRejected() throws Exception {
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        Instant end = Instant.now();

        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .param("startDate", start.toString())
                        .param("endDate", end.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("startDate must not be after endDate")));
    }

    // =========================================================================
    // 6. PAGINATION BOUNDARIES & DETERMINISTIC SORTING
    // =========================================================================

    @Test
    @DisplayName("Negative page index is rejected with 400 Bad Request")
    void negativePageRejected() throws Exception {
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("Page index must not be negative")));
    }

    @Test
    @DisplayName("Page size < 1 is rejected with 400 Bad Request")
    void zeroPageSizeRejected() throws Exception {
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("Page size must be between 1 and 100")));
    }

    @Test
    @DisplayName("Page size > 100 is rejected with 400 Bad Request")
    void oversizedPageSizeRejected() throws Exception {
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value(containsString("Page size must be between 1 and 100")));
    }

    @Test
    @DisplayName("Deterministic newest-first sorting with stable tie-breaker (ORDER BY created_at DESC, id DESC)")
    void deterministicSortingAndPagination() throws Exception {
        UUID product = UUID.randomUUID();
        UUID warehouse = UUID.randomUUID();

        for (int i = 1; i <= 5; i++) {
            auditLogService.logInventoryAdjustment(orgAId, adminUserId, product, warehouse, i, "Adj " + i, null);
        }

        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .param("page", "0")
                        .param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.content", hasSize(3)));

        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .param("page", "1")
                        .param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(true))
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    // =========================================================================
    // 7. SECURITY-EVENT AUDITING (ORGANIZATION_SWITCHED, ACCESS_DENIED, SESSION_REVOKED)
    // =========================================================================

    @Test
    @DisplayName("POST /api/auth/switch-org creates an ORGANIZATION_SWITCHED audit record")
    void organizationSwitchedAudited() throws Exception {
        // Give admin an active membership in Org B as MANAGER
        membershipRepository.save(OrganizationMembership.create(orgBId, adminUserId, Role.MANAGER));

        SwitchOrgRequest switchReq = new SwitchOrgRequest(orgBId);

        mockMvc.perform(post("/api/auth/switch-org")
                        .header("Authorization", "Bearer " + adminJwt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(switchReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizationId").value(orgBId.toString()))
                .andExpect(jsonPath("$.role").value("MANAGER"));

        // Verify audit record was created in orgBId
        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgBId,
                AuditEntityType.ORGANIZATION_MEMBERSHIP,
                AuditAction.ORGANIZATION_SWITCHED,
                adminUserId,
                null,
                null,
                10,
                0
        );

        assertThat(logs).hasSize(1);
        AuditLog switchLog = logs.getFirst();
        assertThat(switchLog.getAction()).isEqualTo(AuditAction.ORGANIZATION_SWITCHED);
        assertThat(switchLog.getActorUserId()).isEqualTo(adminUserId);
        assertThat(switchLog.getMetadata()).contains("MANAGER");
    }

    @Test
    @DisplayName("Authenticated authorization rejection writes an ACCESS_DENIED audit record")
    void accessDeniedAudited() throws Exception {
        // OPERATOR attempts to access /api/audit-logs (forbidden)
        mockMvc.perform(get("/api/audit-logs")
                        .header("Authorization", "Bearer " + operatorJwt)
                        .header("X-Organization-Id", orgAId.toString()))
                .andExpect(status().isForbidden());

        // Verify ACCESS_DENIED audit event was recorded
        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgAId,
                AuditEntityType.AUTH_SESSION,
                AuditAction.ACCESS_DENIED,
                operatorUserId,
                null,
                null,
                10,
                0
        );

        assertThat(logs).hasSize(1);
        AuditLog deniedLog = logs.getFirst();
        assertThat(deniedLog.getAction()).isEqualTo(AuditAction.ACCESS_DENIED);
        assertThat(deniedLog.getActorUserId()).isEqualTo(operatorUserId);
        assertThat(deniedLog.getMetadata()).contains("/api/audit-logs");
    }

    @Test
    @DisplayName("Authenticated POST /api/auth/logout with valid X-Organization-Id records SESSION_REVOKED")
    void logoutAuditedWhenOrgHeaderPresent() throws Exception {
        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgAId.toString())
                        .header("X-StockGuard-CSRF", "1"))
                .andExpect(status().isOk());

        List<AuditLog> logs = auditLogService.findAuditLogs(
                orgAId,
                AuditEntityType.AUTH_SESSION,
                AuditAction.SESSION_REVOKED,
                adminUserId,
                null,
                null,
                10,
                0
        );

        assertThat(logs).hasSize(1);
        AuditLog revokedLog = logs.getFirst();
        assertThat(revokedLog.getAction()).isEqualTo(AuditAction.SESSION_REVOKED);
        assertThat(revokedLog.getActorUserId()).isEqualTo(adminUserId);
        assertThat(revokedLog.getMetadata()).contains("LOGOUT");
    }

    @Test
    @DisplayName("Pre-authentication login failures (bad credentials) do NOT insert any audit_logs records")
    void preAuthenticationFailuresDoNotInsertAuditLogs() throws Exception {
        long initialCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs", Long.class);

        LoginRequest badLogin = new LoginRequest("nonexistent@stockguard.com", "wrong-password");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(badLogin)))
                .andExpect(status().isUnauthorized());

        long afterCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_logs", Long.class);
        assertThat(afterCount).isEqualTo(initialCount);
    }
}
