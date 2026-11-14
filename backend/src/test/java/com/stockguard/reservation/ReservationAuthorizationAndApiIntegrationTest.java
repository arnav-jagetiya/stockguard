package com.stockguard.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockguard.inventory.dto.OpeningBalanceRequest;
import com.stockguard.inventory.service.InventoryService;
import com.stockguard.order.dto.CreateOrderRequest;
import com.stockguard.order.dto.OrderItemRequest;
import com.stockguard.order.dto.OrderResponse;
import com.stockguard.order.service.OrderService;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.product.domain.Product;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.reservation.domain.ReservationStatus;
import com.stockguard.reservation.dto.CreateReservationRequest;
import com.stockguard.security.domain.AuthSession;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.AuthSessionRepository;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.security.service.JwtService;
import com.stockguard.shared.idempotency.IdempotencyService;
import com.stockguard.warehouse.domain.Warehouse;
import com.stockguard.warehouse.repository.WarehouseRepository;
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
class ReservationAuthorizationAndApiIntegrationTest {

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

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = IdempotencyService.createDefaultObjectMapper();

    private UUID orgId;
    private UUID adminUserId;
    private String adminJwt;
    private String managerJwt;
    private String operatorJwt;
    private String viewerJwt;
    private Warehouse warehouse;
    private Product product;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        Organization org = organizationRepository.save(Organization.create("Res Auth Org " + UUID.randomUUID()));
        orgId = org.getId();

        // 1. ADMIN
        User adminUser = userRepository.save(User.create("admin." + UUID.randomUUID() + "@stockguard.com", "hash"));
        adminUserId = adminUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgId, adminUserId, Role.ADMIN));
        AuthSession adminSession = sessionRepository.save(AuthSession.create(adminUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        adminJwt = jwtService.generateAccessToken(adminUserId, adminUser.getEmail(), adminSession.getId());

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

        warehouse = warehouseRepository.save(new Warehouse(orgId, "WH-AUTH-" + UUID.randomUUID().toString().substring(0, 5), "Auth WH", "Loc", 1));
        product = productRepository.save(new Product(orgId, "P-AUTH-" + UUID.randomUUID().toString().substring(0, 5), "Auth Prod", null));

        // Seed ample stock
        inventoryService.recordOpeningBalance(orgId, adminUserId, new OpeningBalanceRequest(product.getId(), warehouse.getId(), 500L, "Initial stock"), null);
    }

    @Test
    @DisplayName("RBAC: ADMIN, MANAGER, and OPERATOR can create reservations; VIEWER is forbidden (403)")
    void reservationCreationRbacMatrix() throws Exception {
        // Create 4 distinct orders
        OrderResponse o1 = orderService.createOrder(orgId, adminUserId, new CreateOrderRequest("C-1", null, "USD", List.of(new OrderItemRequest(product.getId(), 5L, new BigDecimal("10.0000")))), null);
        OrderResponse o2 = orderService.createOrder(orgId, adminUserId, new CreateOrderRequest("C-2", null, "USD", List.of(new OrderItemRequest(product.getId(), 5L, new BigDecimal("10.0000")))), null);
        OrderResponse o3 = orderService.createOrder(orgId, adminUserId, new CreateOrderRequest("C-3", null, "USD", List.of(new OrderItemRequest(product.getId(), 5L, new BigDecimal("10.0000")))), null);
        OrderResponse o4 = orderService.createOrder(orgId, adminUserId, new CreateOrderRequest("C-4", null, "USD", List.of(new OrderItemRequest(product.getId(), 5L, new BigDecimal("10.0000")))), null);

        // 1. ADMIN succeeds (200)
        mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReservationRequest(o1.id()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 2. MANAGER succeeds (200)
        mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + managerJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReservationRequest(o2.id()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 3. OPERATOR succeeds (200) via /api/orders/{id}/reserve endpoint
        mockMvc.perform(post("/api/orders/" + o3.id() + "/reserve")
                        .header("Authorization", "Bearer " + operatorJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        // 4. VIEWER is forbidden (403)
        mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReservationRequest(o4.id()))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("VIEWER role can view reservation by ID and by order ID (200 OK)")
    void viewerCanViewReservation() throws Exception {
        OrderResponse o = orderService.createOrder(orgId, adminUserId, new CreateOrderRequest("C-VIEW", null, "USD", List.of(new OrderItemRequest(product.getId(), 10L, new BigDecimal("10.0000")))), null);

        String createRes = mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReservationRequest(o.id()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String resId = objectMapper.readTree(createRes).get("id").asText();

        // 1. GET /api/reservations/{id}
        mockMvc.perform(get("/api/reservations/" + resId)
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(resId))
                .andExpect(jsonPath("$.orderId").value(o.id().toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.allocations[0].warehouseId").value(warehouse.getId().toString()));

        // 2. GET /api/orders/{orderId}/reservation
        mockMvc.perform(get("/api/orders/" + o.id() + "/reservation")
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(resId));
    }

    @Test
    @DisplayName("Tenant isolation: User B from Org B cannot view or reserve Org A's order")
    void tenantIsolation() throws Exception {
        Organization orgB = organizationRepository.save(Organization.create("Org B " + UUID.randomUUID()));
        User userB = userRepository.save(User.create("userB." + UUID.randomUUID() + "@stockguard.com", "hash"));
        membershipRepository.save(OrganizationMembership.create(orgB.getId(), userB.getId(), Role.ADMIN));
        AuthSession sessionB = sessionRepository.save(AuthSession.create(userB.getId(), Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        String jwtB = jwtService.generateAccessToken(userB.getId(), userB.getEmail(), sessionB.getId());

        OrderResponse orderA = orderService.createOrder(orgId, adminUserId, new CreateOrderRequest("C-A", null, "USD", List.of(new OrderItemRequest(product.getId(), 5L, new BigDecimal("10.0000")))), null);

        String createRes = mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReservationRequest(orderA.id()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String resIdA = objectMapper.readTree(createRes).get("id").asText();

        // User B attempts to view reservation A -> 404 Not Found
        mockMvc.perform(get("/api/reservations/" + resIdA)
                        .header("Authorization", "Bearer " + jwtB)
                        .header("X-Organization-Id", orgB.getId().toString()))
                .andExpect(status().isNotFound());

        // User B attempts to reserve order A under Org B context -> 404 Not Found (order does not exist in Org B)
        mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + jwtB)
                        .header("X-Organization-Id", orgB.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReservationRequest(orderA.id()))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Inactive membership is forbidden from creating or viewing reservations (403 Forbidden)")
    void inactiveMembershipForbidden() throws Exception {
        User inactiveUser = userRepository.save(User.create("inactive.res." + UUID.randomUUID() + "@stockguard.com", "hash"));
        OrganizationMembership membership = OrganizationMembership.create(orgId, inactiveUser.getId(), Role.ADMIN);
        membership.setActive(false);
        membershipRepository.save(membership);

        AuthSession session = sessionRepository.save(AuthSession.create(inactiveUser.getId(), Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        String inactiveJwt = jwtService.generateAccessToken(inactiveUser.getId(), inactiveUser.getEmail(), session.getId());

        OrderResponse o = orderService.createOrder(orgId, adminUserId, new CreateOrderRequest("C-INACT", null, "USD", List.of(new OrderItemRequest(product.getId(), 5L, new BigDecimal("10.0000")))), null);

        mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + inactiveJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReservationRequest(o.id()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("User has no active membership in organization: " + orgId));
    }

    @Test
    @DisplayName("Idempotent reservation: initial returns 200, replay returns original 200 and body, mismatch returns 409")
    void idempotencyReplayAndMismatch() throws Exception {
        OrderResponse o1 = orderService.createOrder(orgId, adminUserId, new CreateOrderRequest("C-IDEM-1", null, "USD", List.of(new OrderItemRequest(product.getId(), 10L, new BigDecimal("10.0000")))), null);
        OrderResponse o2 = orderService.createOrder(orgId, adminUserId, new CreateOrderRequest("C-IDEM-2", null, "USD", List.of(new OrderItemRequest(product.getId(), 10L, new BigDecimal("10.0000")))), null);

        String idemKey = "test-idem-res-" + UUID.randomUUID();

        // 1. Initial execution -> 200 OK
        String initialRes = mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReservationRequest(o1.id()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.orderId").value(o1.id().toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();

        String resId1 = objectMapper.readTree(initialRes).get("id").asText();

        // 2. Replay with identical payload -> 200 OK with identical body
        String replayRes = mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReservationRequest(o1.id()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(resId1))
                .andReturn().getResponse().getContentAsString();

        assertThat(replayRes).isEqualTo(initialRes);

        // Verify exactly 1 reservation exists in DB
        Integer resCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reservations WHERE organization_id = ? AND order_id = ?",
                Integer.class, orgId, o1.id()
        );
        assertThat(resCount).isEqualTo(1);

        // 3. Payload mismatch with same idempotency key -> 409 Conflict
        mockMvc.perform(post("/api/reservations")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateReservationRequest(o2.id()))))
                .andExpect(status().isConflict());
    }
}
