package com.stockguard.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockguard.inventory.dto.OpeningBalanceRequest;
import com.stockguard.inventory.service.InventoryService;
import com.stockguard.order.domain.OrderStatus;
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
import com.stockguard.reservation.dto.CreateReservationRequest;
import com.stockguard.reservation.service.ReservationService;
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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class OrderLifecycleApiAndAuthorizationIntegrationTest {

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
    private ReservationService reservationService;

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

        Organization org = organizationRepository.save(Organization.create("Lifecycle Auth Org " + UUID.randomUUID()));
        orgId = org.getId();

        // 1. ADMIN
        User adminUser = userRepository.save(User.create("admin.lc." + UUID.randomUUID() + "@stockguard.com", "hash"));
        adminUserId = adminUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgId, adminUserId, Role.ADMIN));
        AuthSession adminSession = sessionRepository.save(AuthSession.create(adminUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        adminJwt = jwtService.generateAccessToken(adminUserId, adminUser.getEmail(), adminSession.getId());

        // 2. MANAGER
        User managerUser = userRepository.save(User.create("mgr.lc." + UUID.randomUUID() + "@stockguard.com", "hash"));
        membershipRepository.save(OrganizationMembership.create(orgId, managerUser.getId(), Role.MANAGER));
        AuthSession mgrSession = sessionRepository.save(AuthSession.create(managerUser.getId(), Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        managerJwt = jwtService.generateAccessToken(managerUser.getId(), managerUser.getEmail(), mgrSession.getId());

        // 3. OPERATOR
        User operatorUser = userRepository.save(User.create("op.lc." + UUID.randomUUID() + "@stockguard.com", "hash"));
        membershipRepository.save(OrganizationMembership.create(orgId, operatorUser.getId(), Role.OPERATOR));
        AuthSession opSession = sessionRepository.save(AuthSession.create(operatorUser.getId(), Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        operatorJwt = jwtService.generateAccessToken(operatorUser.getId(), operatorUser.getEmail(), opSession.getId());

        // 4. VIEWER
        User viewerUser = userRepository.save(User.create("vw.lc." + UUID.randomUUID() + "@stockguard.com", "hash"));
        membershipRepository.save(OrganizationMembership.create(orgId, viewerUser.getId(), Role.VIEWER));
        AuthSession vwSession = sessionRepository.save(AuthSession.create(viewerUser.getId(), Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        viewerJwt = jwtService.generateAccessToken(viewerUser.getId(), viewerUser.getEmail(), vwSession.getId());

        warehouse = warehouseRepository.save(new Warehouse(orgId, "WH-LC-A-" + UUID.randomUUID().toString().substring(0, 5), "LC Auth WH", "Loc", 1));
        product = productRepository.save(new Product(orgId, "P-LC-A-" + UUID.randomUUID().toString().substring(0, 5), "LC Auth Prod", null));

        inventoryService.recordOpeningBalance(orgId, adminUserId, new OpeningBalanceRequest(product.getId(), warehouse.getId(), 500L, "Seed stock"), null);
    }

    @Test
    @DisplayName("RBAC: ADMIN, MANAGER, OPERATOR can confirm orders; VIEWER is forbidden (403)")
    void confirmOrderRbacMatrix() throws Exception {
        OrderResponse o1 = createAndReserveOrder("C-CONF-1");
        OrderResponse o2 = createAndReserveOrder("C-CONF-2");
        OrderResponse o3 = createAndReserveOrder("C-CONF-3");
        OrderResponse o4 = createAndReserveOrder("C-CONF-4");

        // 1. ADMIN succeeds (200)
        mockMvc.perform(post("/api/orders/" + o1.id() + "/confirm")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 2. MANAGER succeeds (200)
        mockMvc.perform(post("/api/orders/" + o2.id() + "/confirm")
                        .header("Authorization", "Bearer " + managerJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 3. OPERATOR succeeds (200)
        mockMvc.perform(post("/api/orders/" + o3.id() + "/confirm")
                        .header("Authorization", "Bearer " + operatorJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));

        // 4. VIEWER is forbidden (403)
        mockMvc.perform(post("/api/orders/" + o4.id() + "/confirm")
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RBAC: ADMIN, MANAGER, OPERATOR can cancel orders; VIEWER is forbidden (403)")
    void cancelOrderRbacMatrix() throws Exception {
        OrderResponse o1 = createAndReserveOrder("C-CANC-1");
        OrderResponse o2 = createAndReserveOrder("C-CANC-2");
        OrderResponse o3 = createAndReserveOrder("C-CANC-3");
        OrderResponse o4 = createAndReserveOrder("C-CANC-4");

        // 1. ADMIN succeeds (200)
        mockMvc.perform(post("/api/orders/" + o1.id() + "/cancel")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 2. MANAGER succeeds (200)
        mockMvc.perform(post("/api/orders/" + o2.id() + "/cancel")
                        .header("Authorization", "Bearer " + managerJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 3. OPERATOR succeeds (200)
        mockMvc.perform(post("/api/orders/" + o3.id() + "/cancel")
                        .header("Authorization", "Bearer " + operatorJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // 4. VIEWER is forbidden (403)
        mockMvc.perform(post("/api/orders/" + o4.id() + "/cancel")
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RBAC: ADMIN, MANAGER, OPERATOR can fulfill confirmed orders; VIEWER is forbidden (403)")
    void fulfillOrderRbacMatrix() throws Exception {
        OrderResponse o1 = createAndConfirmOrder("C-FULF-1");
        OrderResponse o2 = createAndConfirmOrder("C-FULF-2");
        OrderResponse o3 = createAndConfirmOrder("C-FULF-3");
        OrderResponse o4 = createAndConfirmOrder("C-FULF-4");

        // 1. ADMIN succeeds (200)
        mockMvc.perform(post("/api/orders/" + o1.id() + "/fulfill")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"));

        // 2. MANAGER succeeds (200)
        mockMvc.perform(post("/api/orders/" + o2.id() + "/fulfill")
                        .header("Authorization", "Bearer " + managerJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"));

        // 3. OPERATOR succeeds (200)
        mockMvc.perform(post("/api/orders/" + o3.id() + "/fulfill")
                        .header("Authorization", "Bearer " + operatorJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"));

        // 4. VIEWER is forbidden (403)
        mockMvc.perform(post("/api/orders/" + o4.id() + "/fulfill")
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Tenant Isolation: User from Org B cannot confirm, cancel, or fulfill Org A's order (404)")
    void tenantIsolationOnLifecycleOperations() throws Exception {
        Organization orgB = organizationRepository.save(Organization.create("Org B Lifecycle " + UUID.randomUUID()));
        User userB = userRepository.save(User.create("userB.lc." + UUID.randomUUID() + "@stockguard.com", "hash"));
        membershipRepository.save(OrganizationMembership.create(orgB.getId(), userB.getId(), Role.ADMIN));
        AuthSession sessionB = sessionRepository.save(AuthSession.create(userB.getId(), Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        String jwtB = jwtService.generateAccessToken(userB.getId(), userB.getEmail(), sessionB.getId());

        OrderResponse orderA = createAndReserveOrder("C-TENANT-A");

        // Org B confirms Org A's order -> 404
        mockMvc.perform(post("/api/orders/" + orderA.id() + "/confirm")
                        .header("Authorization", "Bearer " + jwtB)
                        .header("X-Organization-Id", orgB.getId().toString()))
                .andExpect(status().isNotFound());

        // Org B cancels Org A's order -> 404
        mockMvc.perform(post("/api/orders/" + orderA.id() + "/cancel")
                        .header("Authorization", "Bearer " + jwtB)
                        .header("X-Organization-Id", orgB.getId().toString()))
                .andExpect(status().isNotFound());

        // Org B fulfills Org A's order -> 404
        mockMvc.perform(post("/api/orders/" + orderA.id() + "/fulfill")
                        .header("Authorization", "Bearer " + jwtB)
                        .header("X-Organization-Id", orgB.getId().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Inactive membership is forbidden from lifecycle operations (403 Forbidden)")
    void inactiveMembershipForbidden() throws Exception {
        User inactiveUser = userRepository.save(User.create("inactive.lc." + UUID.randomUUID() + "@stockguard.com", "hash"));
        OrganizationMembership membership = OrganizationMembership.create(orgId, inactiveUser.getId(), Role.ADMIN);
        membership.setActive(false);
        membershipRepository.save(membership);

        AuthSession session = sessionRepository.save(AuthSession.create(inactiveUser.getId(), Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        String inactiveJwt = jwtService.generateAccessToken(inactiveUser.getId(), inactiveUser.getEmail(), session.getId());

        OrderResponse order = createAndReserveOrder("C-INACT-MEM");

        mockMvc.perform(post("/api/orders/" + order.id() + "/confirm")
                        .header("Authorization", "Bearer " + inactiveJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("User has no active membership in organization: " + orgId));
    }

    @Test
    @DisplayName("Idempotency: Replaying confirm with same key returns original 200 and body; payload mismatch returns 409")
    void confirmIdempotencyReplayAndMismatch() throws Exception {
        OrderResponse o1 = createAndReserveOrder("C-IDEM-CONF-1");
        OrderResponse o2 = createAndReserveOrder("C-IDEM-CONF-2");

        String key = "test-idem-confirm-" + UUID.randomUUID();

        // 1. Initial confirmation
        String initialRes = mockMvc.perform(post("/api/orders/" + o1.id() + "/confirm")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn().getResponse().getContentAsString();

        // 2. Replay with same key on same order -> 200 OK with identical body
        String replayRes = mockMvc.perform(post("/api/orders/" + o1.id() + "/confirm")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(replayRes).isEqualTo(initialRes);

        // 3. Payload mismatch with same key on different order -> 409 Conflict
        mockMvc.perform(post("/api/orders/" + o2.id() + "/confirm")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .header("Idempotency-Key", key))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Idempotency: Replaying fulfill with same key returns original 200 and body; payload mismatch returns 409")
    void fulfillIdempotencyReplayAndMismatch() throws Exception {
        OrderResponse o1 = createAndConfirmOrder("C-IDEM-FULF-1");
        OrderResponse o2 = createAndConfirmOrder("C-IDEM-FULF-2");

        String key = "test-idem-fulfill-" + UUID.randomUUID();

        // 1. Initial fulfillment
        String initialRes = mockMvc.perform(post("/api/orders/" + o1.id() + "/fulfill")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"))
                .andReturn().getResponse().getContentAsString();

        // 2. Replay with same key on same order -> 200 OK with identical body
        String replayRes = mockMvc.perform(post("/api/orders/" + o1.id() + "/fulfill")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULFILLED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(replayRes).isEqualTo(initialRes);

        // 3. Payload mismatch with same key on different order -> 409 Conflict
        mockMvc.perform(post("/api/orders/" + o2.id() + "/fulfill")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .header("Idempotency-Key", key))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("Concurrent duplicate lifecycle requests execute business logic once and replay")
    void concurrentDuplicateConfirmRequests() throws Exception {
        OrderResponse o = createAndReserveOrder("C-CONC-CONF");
        String key = "test-concurrent-confirm-" + UUID.randomUUID();

        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        List<Future<Integer>> futures = new CopyOnWriteArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(executor.submit(() -> {
                startLatch.await();
                var result = mockMvc.perform(post("/api/orders/" + o.id() + "/confirm")
                                .header("Authorization", "Bearer " + adminJwt)
                                .header("X-Organization-Id", orgId.toString())
                                .header("Idempotency-Key", key))
                        .andReturn();
                int sc = result.getResponse().getStatus();
                if (sc == 200) {
                    successCount.incrementAndGet();
                }
                return sc;
            }));
        }

        startLatch.countDown();
        for (Future<Integer> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // All threads get 200 OK
        assertThat(successCount.get()).isEqualTo(threads);

        // Exactly one idempotency record stored
        Integer idempRecords = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM idempotency_records WHERE organization_id = ? AND idempotency_key = ?",
                Integer.class, orgId, key
        );
        assertThat(idempRecords).isEqualTo(1);
    }

    private OrderResponse createAndReserveOrder(String custRef) {
        OrderResponse o = orderService.createOrder(orgId, adminUserId, new CreateOrderRequest(custRef, null, "USD", List.of(new OrderItemRequest(product.getId(), 5L, new BigDecimal("10.0000")))), null);
        reservationService.createReservation(orgId, adminUserId, o.id(), null);
        return o;
    }

    private OrderResponse createAndConfirmOrder(String custRef) {
        OrderResponse o = createAndReserveOrder(custRef);
        return orderService.confirmOrder(orgId, adminUserId, o.id(), null);
    }
}
