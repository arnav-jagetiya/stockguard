package com.stockguard.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockguard.inventory.dto.AdjustStockRequest;
import com.stockguard.inventory.dto.ReceiveStockRequest;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.product.domain.Product;
import com.stockguard.product.dto.CreateProductRequest;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.security.domain.AuthSession;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.AuthSessionRepository;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.security.service.JwtService;
import com.stockguard.warehouse.domain.Warehouse;
import com.stockguard.warehouse.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class InventoryAuthorizationAndApiIntegrationTest {

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
    private ProductRepository productRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private UUID orgId;
    private UUID adminUserId;
    private UUID operatorUserId;
    private UUID viewerUserId;

    private String adminJwt;
    private String operatorJwt;
    private String viewerJwt;

    private UUID productId;
    private UUID warehouseId;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        Organization org = organizationRepository.save(Organization.create("Auth Org " + UUID.randomUUID()));
        orgId = org.getId();

        // 1. Create ADMIN user & session
        User adminUser = userRepository.save(User.create("admin." + UUID.randomUUID() + "@stockguard.com", "hash"));
        adminUserId = adminUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgId, adminUserId, Role.ADMIN));
        AuthSession adminSession = sessionRepository.save(AuthSession.create(adminUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        adminJwt = jwtService.generateAccessToken(adminUserId, adminUser.getEmail(), adminSession.getId());

        // 2. Create OPERATOR user & session
        User operatorUser = userRepository.save(User.create("operator." + UUID.randomUUID() + "@stockguard.com", "hash"));
        operatorUserId = operatorUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgId, operatorUserId, Role.OPERATOR));
        AuthSession opSession = sessionRepository.save(AuthSession.create(operatorUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        operatorJwt = jwtService.generateAccessToken(operatorUserId, operatorUser.getEmail(), opSession.getId());

        // 3. Create VIEWER user & session
        User viewerUser = userRepository.save(User.create("viewer." + UUID.randomUUID() + "@stockguard.com", "hash"));
        viewerUserId = viewerUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgId, viewerUserId, Role.VIEWER));
        AuthSession viewerSession = sessionRepository.save(AuthSession.create(viewerUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        viewerJwt = jwtService.generateAccessToken(viewerUserId, viewerUser.getEmail(), viewerSession.getId());

        Product product = productRepository.save(new Product(orgId, "SKU-AUTH-" + UUID.randomUUID().toString().substring(0, 6), "Auth Prod", null));
        productId = product.getId();

        Warehouse warehouse = warehouseRepository.save(new Warehouse(orgId, "WH-AUTH-" + UUID.randomUUID().toString().substring(0, 6), "Auth WH", "Loc", 1));
        warehouseId = warehouse.getId();
    }

    @Test
    @DisplayName("ADMIN can create products and execute reconciliation")
    void adminCanManageProductsAndReconcile() throws Exception {
        CreateProductRequest req = new CreateProductRequest("SKU-ADMIN-NEW", "Admin Product", null);

        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.normalizedSku").value("SKU-ADMIN-NEW"));

        mockMvc.perform(get("/api/inventory/reconciliation")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBalancesInspected").isNumber());
    }

    @Test
    @DisplayName("OPERATOR can receive and adjust inventory, but cannot create products or reconcile")
    void operatorPermissionsEnforced() throws Exception {
        // OPERATOR can receive stock
        ReceiveStockRequest receiveReq = new ReceiveStockRequest(productId, warehouseId, 20L, null, "Receipt");
        mockMvc.perform(post("/api/inventory/receipts")
                        .header("Authorization", "Bearer " + operatorJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(receiveReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionType").value("RECEIPT"));

        // OPERATOR can adjust stock
        AdjustStockRequest adjustReq = new AdjustStockRequest(productId, warehouseId, 5L, "Found extra", null);
        mockMvc.perform(post("/api/inventory/adjustments")
                        .header("Authorization", "Bearer " + operatorJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(adjustReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionType").value("ADJUSTMENT"));

        // OPERATOR CANNOT create product (requires product:manage) -> 403 Forbidden
        CreateProductRequest prodReq = new CreateProductRequest("SKU-FAIL", "Fail Product", null);
        mockMvc.perform(post("/api/products")
                        .header("Authorization", "Bearer " + operatorJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(prodReq)))
                .andExpect(status().isForbidden());

        // OPERATOR CANNOT reconcile inventory (requires inventory:reconcile) -> 403 Forbidden
        mockMvc.perform(get("/api/inventory/reconciliation")
                        .header("Authorization", "Bearer " + operatorJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("VIEWER can view balances but cannot mutate inventory")
    void viewerReadOnlyAccessEnforced() throws Exception {
        // VIEWER can view balances
        mockMvc.perform(get("/api/inventory/balances")
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk());

        // VIEWER CANNOT receive stock -> 403 Forbidden
        ReceiveStockRequest receiveReq = new ReceiveStockRequest(productId, warehouseId, 10L, null, "Viewer attempt");
        mockMvc.perform(post("/api/inventory/receipts")
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(receiveReq)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Missing X-Organization-Id header returns 400 Bad Request")
    void missingOrganizationHeaderFailsClosed() throws Exception {
        mockMvc.perform(get("/api/products")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing mandatory organization header 'X-Organization-Id'"));
    }
}
