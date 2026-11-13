package com.stockguard.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockguard.inventory.dto.OpeningBalanceRequest;
import com.stockguard.inventory.service.InventoryService;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.product.domain.Product;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.security.domain.AuthSession;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.AuthSessionRepository;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.security.service.JwtService;
import com.stockguard.transfer.dto.CreateTransferRequest;
import com.stockguard.transfer.dto.TransferItemRequest;
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
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class TransferAuthorizationAndApiIntegrationTest {

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
    private com.stockguard.inventory.repository.InventoryBalanceRepository balanceRepository;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = com.stockguard.shared.idempotency.IdempotencyService.createDefaultObjectMapper();

    private UUID orgId;
    private UUID adminUserId;
    private UUID managerUserId;
    private UUID operatorUserId;
    private UUID viewerUserId;

    private String adminJwt;
    private String managerJwt;
    private String operatorJwt;
    private String viewerJwt;

    private Warehouse warehouse1;
    private Warehouse warehouse2;
    private Product product;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        Organization org = organizationRepository.save(Organization.create("Transfer Auth Org " + UUID.randomUUID()));
        orgId = org.getId();

        // 1. ADMIN
        User adminUser = userRepository.save(User.create("admin." + UUID.randomUUID() + "@stockguard.com", "hash"));
        adminUserId = adminUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgId, adminUserId, Role.ADMIN));
        AuthSession adminSession = sessionRepository.save(AuthSession.create(adminUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        adminJwt = jwtService.generateAccessToken(adminUserId, adminUser.getEmail(), adminSession.getId());

        // 2. MANAGER
        User managerUser = userRepository.save(User.create("mgr." + UUID.randomUUID() + "@stockguard.com", "hash"));
        managerUserId = managerUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgId, managerUserId, Role.MANAGER));
        AuthSession mgrSession = sessionRepository.save(AuthSession.create(managerUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        managerJwt = jwtService.generateAccessToken(managerUserId, managerUser.getEmail(), mgrSession.getId());

        // 3. OPERATOR
        User operatorUser = userRepository.save(User.create("op." + UUID.randomUUID() + "@stockguard.com", "hash"));
        operatorUserId = operatorUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgId, operatorUserId, Role.OPERATOR));
        AuthSession opSession = sessionRepository.save(AuthSession.create(operatorUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        operatorJwt = jwtService.generateAccessToken(operatorUserId, operatorUser.getEmail(), opSession.getId());

        // 4. VIEWER
        User viewerUser = userRepository.save(User.create("vw." + UUID.randomUUID() + "@stockguard.com", "hash"));
        viewerUserId = viewerUser.getId();
        membershipRepository.save(OrganizationMembership.create(orgId, viewerUserId, Role.VIEWER));
        AuthSession vwSession = sessionRepository.save(AuthSession.create(viewerUserId, Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        viewerJwt = jwtService.generateAccessToken(viewerUserId, viewerUser.getEmail(), vwSession.getId());

        warehouse1 = warehouseRepository.save(new Warehouse(orgId, "W1-AUTH-" + UUID.randomUUID().toString().substring(0, 5), "Warehouse 1", "Loc 1", 1));
        warehouse2 = warehouseRepository.save(new Warehouse(orgId, "W2-AUTH-" + UUID.randomUUID().toString().substring(0, 5), "Warehouse 2", "Loc 2", 2));

        product = productRepository.save(new Product(orgId, "P-AUTH-" + UUID.randomUUID().toString().substring(0, 5), "Auth Prod", null));

        // Initial stock = 500 at warehouse 1
        inventoryService.recordOpeningBalance(orgId, adminUserId, new OpeningBalanceRequest(product.getId(), warehouse1.getId(), 500L, "Initial stock"), null);
    }

    @Test
    @DisplayName("ADMIN, MANAGER, and OPERATOR can execute transfers; VIEWER is forbidden (403)")
    void transferExecutionRbacMatrix() throws Exception {
        CreateTransferRequest reqAdmin = new CreateTransferRequest(
                warehouse1.getId(),
                warehouse2.getId(),
                List.of(new TransferItemRequest(product.getId(), 10L)),
                "Admin transfer"
        );

        // 1. ADMIN succeeds (201)
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqAdmin)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.sourceWarehouseId").value(warehouse1.getId().toString()))
                .andExpect(jsonPath("$.destinationWarehouseId").value(warehouse2.getId().toString()));

        // 2. MANAGER succeeds (201)
        CreateTransferRequest reqMgr = new CreateTransferRequest(
                warehouse1.getId(),
                warehouse2.getId(),
                List.of(new TransferItemRequest(product.getId(), 15L)),
                "Manager transfer"
        );
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + managerJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqMgr)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].quantity").value(15));

        // 3. OPERATOR succeeds (201)
        CreateTransferRequest reqOp = new CreateTransferRequest(
                warehouse1.getId(),
                warehouse2.getId(),
                List.of(new TransferItemRequest(product.getId(), 20L)),
                "Operator transfer"
        );
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + operatorJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqOp)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].quantity").value(20));

        // 4. VIEWER fails with 403 Forbidden
        CreateTransferRequest reqViewer = new CreateTransferRequest(
                warehouse1.getId(),
                warehouse2.getId(),
                List.of(new TransferItemRequest(product.getId(), 5L)),
                "Viewer illegal transfer"
        );
        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqViewer)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("All roles including VIEWER can view transfers and transfer details")
    void allRolesCanViewTransfers() throws Exception {
        // Execute a transfer as ADMIN
        CreateTransferRequest req = new CreateTransferRequest(
                warehouse1.getId(),
                warehouse2.getId(),
                List.of(new TransferItemRequest(product.getId(), 25L)),
                "Pre-populated transfer"
        );

        String jsonResponse = mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String transferId = objectMapper.readTree(jsonResponse).get("id").asText();

        // VIEWER can list transfers
        mockMvc.perform(get("/api/transfers")
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").isNotEmpty());

        // VIEWER can view specific transfer
        mockMvc.perform(get("/api/transfers/" + transferId)
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transferId))
                .andExpect(jsonPath("$.items[0].quantity").value(25));
    }

    @Test
    @DisplayName("Tenant isolation prevents users from accessing transfers belonging to other organizations")
    void tenantIsolationEnforcedOnTransfers() throws Exception {
        // 1. Create a transfer in Org A
        CreateTransferRequest req = new CreateTransferRequest(
                warehouse1.getId(),
                warehouse2.getId(),
                List.of(new TransferItemRequest(product.getId(), 10L)),
                "Org A transfer"
        );

        String jsonResponse = mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String transferIdOrgA = objectMapper.readTree(jsonResponse).get("id").asText();

        // 2. Create Org B and user in Org B
        Organization orgB = organizationRepository.save(Organization.create("Org B " + UUID.randomUUID()));
        User userB = userRepository.save(User.create("user.b." + UUID.randomUUID() + "@stockguard.com", "hash"));
        membershipRepository.save(OrganizationMembership.create(orgB.getId(), userB.getId(), Role.ADMIN));
        AuthSession sessionB = sessionRepository.save(AuthSession.create(userB.getId(), Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        String jwtB = jwtService.generateAccessToken(userB.getId(), userB.getEmail(), sessionB.getId());

        // 3. User in Org B queries transfer created in Org A -> returns 404 Not Found
        mockMvc.perform(get("/api/transfers/" + transferIdOrgA)
                        .header("Authorization", "Bearer " + jwtB)
                        .header("X-Organization-Id", orgB.getId().toString()))
                .andExpect(status().isNotFound());

        // 4. User in Org B listing transfers gets empty list
        mockMvc.perform(get("/api/transfers")
                        .header("Authorization", "Bearer " + jwtB)
                        .header("X-Organization-Id", orgB.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("Unauthenticated request is rejected with 401 Unauthorized")
    void unauthenticatedRequestRejected() throws Exception {
        mockMvc.perform(get("/api/transfers")
                        .header("X-Organization-Id", orgId.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("HTTP-level idempotency replay: POST /api/transfers returns 201 Created on replay, persists 201, and mutates only once")
    void idempotentTransferHttpReplay() throws Exception {
        String idempotencyKey = "http-idem-key-" + UUID.randomUUID();

        CreateTransferRequest req = new CreateTransferRequest(
                warehouse1.getId(),
                warehouse2.getId(),
                List.of(new TransferItemRequest(product.getId(), 25L)),
                "Idempotent HTTP transfer"
        );

        // 1. First execution: HTTP 201 Created
        org.springframework.test.web.servlet.MvcResult result1 = mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        String body1 = result1.getResponse().getContentAsString();
        com.stockguard.transfer.dto.TransferResponse resp1 = objectMapper.readValue(body1, com.stockguard.transfer.dto.TransferResponse.class);
        org.assertj.core.api.Assertions.assertThat(resp1.id()).isNotNull();

        // 2. Repeat exact request with the same key: HTTP 201 Created
        org.springframework.test.web.servlet.MvcResult result2 = mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", orgId.toString())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        String body2 = result2.getResponse().getContentAsString();
        com.stockguard.transfer.dto.TransferResponse resp2 = objectMapper.readValue(body2, com.stockguard.transfer.dto.TransferResponse.class);

        // 3. Assert exact same transfer ID and response attributes
        org.assertj.core.api.Assertions.assertThat(resp2.id()).isEqualTo(resp1.id());
        org.assertj.core.api.Assertions.assertThat(resp2.transactionId()).isEqualTo(resp1.transactionId());
        org.assertj.core.api.Assertions.assertThat(resp2.items()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(resp2.items().getFirst().id()).isEqualTo(resp1.items().getFirst().id());

        // 4. Assert response_status in idempotency_records is 201
        Integer persistedStatus = jdbcTemplate.queryForObject(
                "SELECT response_status FROM idempotency_records WHERE organization_id = ? AND idempotency_key = ?",
                Integer.class,
                orgId,
                idempotencyKey
        );
        org.assertj.core.api.Assertions.assertThat(persistedStatus).isEqualTo(201);

        // 5. Assert source and destination balances changed only once (initial stock was 500)
        com.stockguard.inventory.domain.InventoryBalance srcBal = balanceRepository
                .findByOrganizationIdAndProductIdAndWarehouseId(orgId, product.getId(), warehouse1.getId())
                .orElseThrow();
        com.stockguard.inventory.domain.InventoryBalance dstBal = balanceRepository
                .findByOrganizationIdAndProductIdAndWarehouseId(orgId, product.getId(), warehouse2.getId())
                .orElseThrow();

        org.assertj.core.api.Assertions.assertThat(srcBal.getOnHand()).isEqualTo(475L);
        org.assertj.core.api.Assertions.assertThat(dstBal.getOnHand()).isEqualTo(25L);

        // 6. Assert exactly one transfer header, one transfer item, and one paired ledger set (2 entries)
        int transferCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transfers WHERE organization_id = ? AND id = ?",
                Integer.class, orgId, resp1.id()
        );
        int itemCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM transfer_items WHERE organization_id = ? AND transfer_id = ?",
                Integer.class, orgId, resp1.id()
        );
        int ledgerCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM inventory_ledger_entries WHERE organization_id = ? AND transaction_id = ?",
                Integer.class, orgId, resp1.transactionId()
        );

        org.assertj.core.api.Assertions.assertThat(transferCount).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(itemCount).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(ledgerCount).isEqualTo(2);
    }
}
