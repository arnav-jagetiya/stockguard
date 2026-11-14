package com.stockguard.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockguard.order.dto.CreateOrderRequest;
import com.stockguard.order.dto.OrderItemRequest;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.product.domain.Product;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.security.domain.AuthSession;
import com.stockguard.security.domain.User;
import com.stockguard.security.service.JwtService;
import com.stockguard.security.repository.AuthSessionRepository;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.shared.idempotency.IdempotencyService;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class OrderAuthorizationAndApiIntegrationTest {

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
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = IdempotencyService.createDefaultObjectMapper();

    private UUID orgId;
    private String adminJwt;
    private String managerJwt;
    private String operatorJwt;
    private String viewerJwt;
    private Product product;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        Organization org = organizationRepository.save(Organization.create("Order Auth Org " + UUID.randomUUID()));
        orgId = org.getId();

        // 1. ADMIN
        User adminUser = userRepository.save(User.create("admin." + UUID.randomUUID() + "@stockguard.com", "hash"));
        membershipRepository.save(OrganizationMembership.create(orgId, adminUser.getId(), Role.ADMIN));
        AuthSession adminSession = sessionRepository.save(AuthSession.create(adminUser.getId(), Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        adminJwt = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), adminSession.getId());

        // 2. MANAGER
        User managerUser = userRepository.save(User.create("mgr." + UUID.randomUUID() + "@stockguard.com", "hash"));
        membershipRepository.save(OrganizationMembership.create(orgId, managerUser.getId(), Role.MANAGER));
        AuthSession mgrSession = sessionRepository.save(AuthSession.create(managerUser.getId(), Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        managerJwt = jwtService.generateAccessToken(managerUser.getId(), managerUser.getEmail(), mgrSession.getId());

        // 3. OPERATOR
        User operatorUser = userRepository.save(User.create("op." + UUID.randomUUID() + "@stockguard.com", "hash"));
        membershipRepository.save(OrganizationMembership.create(orgId, operatorUser.getId(), Role.OPERATOR));
        AuthSession opSession = sessionRepository.save(AuthSession.create(operatorUser.getId(), Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        operatorJwt = jwtService.generateAccessToken(operatorUser.getId(), operatorUser.getEmail(), opSession.getId());

        // 4. VIEWER
        User viewerUser = userRepository.save(User.create("vw." + UUID.randomUUID() + "@stockguard.com", "hash"));
        membershipRepository.save(OrganizationMembership.create(orgId, viewerUser.getId(), Role.VIEWER));
        AuthSession vwSession = sessionRepository.save(AuthSession.create(viewerUser.getId(), Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        viewerJwt = jwtService.generateAccessToken(viewerUser.getId(), viewerUser.getEmail(), vwSession.getId());

        product = productRepository.save(new Product(orgId, "P-AUTH-" + UUID.randomUUID().toString().substring(0, 5), "Auth Prod", null));
    }

    @Test
    @DisplayName("ADMIN, MANAGER, and OPERATOR can create orders; VIEWER is forbidden (403)")
    void orderCreationRbacMatrix() throws Exception {
        CreateOrderRequest reqAdmin = new CreateOrderRequest(
                "CUST-ADMIN", "EXT-1", "USD",
                List.of(new OrderItemRequest(product.getId(), 2L, new BigDecimal("25.0000")))
        );

        // 1. ADMIN succeeds (201)
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqAdmin)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.totalAmount").value(50.0));

        // 2. MANAGER succeeds (201)
        CreateOrderRequest reqMgr = new CreateOrderRequest(
                "CUST-MGR", "EXT-2", "USD",
                List.of(new OrderItemRequest(product.getId(), 1L, new BigDecimal("25.0000")))
        );
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + managerJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqMgr)))
                .andExpect(status().isCreated());

        // 3. OPERATOR succeeds (201)
        CreateOrderRequest reqOp = new CreateOrderRequest(
                "CUST-OP", "EXT-3", "USD",
                List.of(new OrderItemRequest(product.getId(), 3L, new BigDecimal("25.0000")))
        );
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + operatorJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqOp)))
                .andExpect(status().isCreated());

        // 4. VIEWER is rejected (403 Forbidden)
        CreateOrderRequest reqVw = new CreateOrderRequest(
                "CUST-VW", "EXT-4", "USD",
                List.of(new OrderItemRequest(product.getId(), 1L, new BigDecimal("25.0000")))
        );
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqVw)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("VIEWER role can view orders and order details (200 OK)")
    void viewerCanViewOrders() throws Exception {
        CreateOrderRequest req = new CreateOrderRequest(
                "CUST-VIEW", "EXT-VW", "USD",
                List.of(new OrderItemRequest(product.getId(), 1L, new BigDecimal("30.0000")))
        );

        String createRes = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String orderId = objectMapper.readTree(createRes).get("id").asText();

        // VIEWER can list orders
        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").value(orderId));

        // VIEWER can get order detail
        mockMvc.perform(get("/api/orders/" + orderId)
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(orderId))
                .andExpect(jsonPath("$.items[0].skuSnapshot").value(product.getSku()));
    }

    @Test
    @DisplayName("Cross-organization isolation: Orders from Org A cannot be accessed from Org B")
    void tenantIsolation() throws Exception {
        Organization orgB = organizationRepository.save(Organization.create("Org B " + UUID.randomUUID()));
        User userB = userRepository.save(User.create("userB." + UUID.randomUUID() + "@stockguard.com", "hash"));
        membershipRepository.save(OrganizationMembership.create(orgB.getId(), userB.getId(), Role.ADMIN));
        AuthSession sessionB = sessionRepository.save(AuthSession.create(userB.getId(), Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        String jwtB = jwtService.generateAccessToken(userB.getId(), userB.getEmail(), sessionB.getId());

        // Create order in Org A
        CreateOrderRequest req = new CreateOrderRequest(
                "CUST-A", null, "USD",
                List.of(new OrderItemRequest(product.getId(), 1L, new BigDecimal("45.0000")))
        );
        String createRes = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String orderIdA = objectMapper.readTree(createRes).get("id").asText();

        // User B attempts to access Org A's order -> 404 Not Found
        mockMvc.perform(get("/api/orders/" + orderIdA)
                        .header("Authorization", "Bearer " + jwtB)
                        .header("X-Organization-Id", orgB.getId().toString()))
                .andExpect(status().isNotFound());

        // User B listing orders in Org B sees empty list
        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + jwtB)
                        .header("X-Organization-Id", orgB.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("Idempotent order creation: replay preserves 201 Created status and body; mismatch returns 409")
    void idempotencyReplayAndMismatch() throws Exception {
        String idemKey = "test-idem-order-" + UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(
                "CUST-IDEM", "EXT-IDEM", "USD",
                List.of(new OrderItemRequest(product.getId(), 5L, new BigDecimal("12.5000")))
        );

        // 1. Initial execution -> 201 Created
        String initialRes = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String orderId1 = objectMapper.readTree(initialRes).get("id").asText();
        String orderNumber1 = objectMapper.readTree(initialRes).get("orderNumber").asText();

        // 2. Replay with identical payload -> 201 Created with identical ID
        String replayRes = mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(orderId1))
                .andExpect(jsonPath("$.orderNumber").value(orderNumber1))
                .andReturn().getResponse().getContentAsString();

        assertThat(replayRes).isEqualTo(initialRes);

        // Verify database: exactly 1 order row exists
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE organization_id = ? AND id = ?",
                Integer.class,
                orgId, UUID.fromString(orderId1)
        );
        assertThat(count).isEqualTo(1);

        // 3. Payload mismatch with same key -> 409 Conflict
        CreateOrderRequest mismatched = new CreateOrderRequest(
                "CUST-DIFFERENT", "EXT-IDEM", "USD",
                List.of(new OrderItemRequest(product.getId(), 10L, new BigDecimal("12.5000")))
        );

        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(mismatched)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("GET /api/orders with limit=500 is capped at 200 items")
    void listOrdersPaginationCappedAt200() throws Exception {
        // Fast batch insert 205 orders into the database for orgId
        List<Object[]> batchParams = new ArrayList<>();
        for (int i = 1; i <= 205; i++) {
            batchParams.add(new Object[]{
                    UUID.randomUUID(),
                    orgId,
                    "ORD-PAGE-" + i + "-" + UUID.randomUUID(),
                    "PENDING",
                    "USD",
                    new BigDecimal("10.0000")
            });
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO orders (id, organization_id, order_number, status, currency, total_amount, created_at, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
                batchParams
        );

        mockMvc.perform(get("/api/orders?limit=500")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(200));
    }

    @Test
    @DisplayName("Inactive membership is forbidden from accessing order endpoints (403 Forbidden)")
    void inactiveMembershipCannotAccessOrders() throws Exception {
        User inactiveUser = userRepository.save(User.create("inactive." + UUID.randomUUID() + "@stockguard.com", "hash"));
        OrganizationMembership membership = OrganizationMembership.create(orgId, inactiveUser.getId(), Role.ADMIN);
        membership.setActive(false);
        membershipRepository.save(membership);

        AuthSession session = sessionRepository.save(AuthSession.create(inactiveUser.getId(), Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        String inactiveJwt = jwtService.generateAccessToken(inactiveUser.getId(), inactiveUser.getEmail(), session.getId());

        // 1. POST /api/orders forbidden
        CreateOrderRequest req = new CreateOrderRequest(
                "CUST-INACTIVE-USER", null, "USD",
                List.of(new OrderItemRequest(product.getId(), 1L, new BigDecimal("10.0000")))
        );
        mockMvc.perform(post("/api/orders")
                        .header("Authorization", "Bearer " + inactiveJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("User has no active membership in organization: " + orgId));

        // 2. GET /api/orders forbidden
        mockMvc.perform(get("/api/orders")
                        .header("Authorization", "Bearer " + inactiveJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("User has no active membership in organization: " + orgId));
    }
}
