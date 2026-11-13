package com.stockguard.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.dto.AddMemberRequest;
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
import org.springframework.security.crypto.password.PasswordEncoder;
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

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end integration test exercising the complete, live Spring Security filter chain
 * against real PostgreSQL Testcontainers without mocked security filters.
 * <p>
 * Verifies the full pipeline:
 * <ol>
 *   <li>{@link RateLimitingFilter}</li>
 *   <li>{@link CsrfProtectionFilter}</li>
 *   <li>{@link JwtAuthenticationFilter} with database-backed session validation ({@link com.stockguard.security.service.JdbcDatabaseSessionValidator})</li>
 *   <li>{@link OrganizationContextFilter} with database-backed membership & permission resolution ({@link com.stockguard.security.service.JdbcTenantMembershipValidator})</li>
 *   <li>Spring Security method authorization ({@code @PreAuthorize}) and controller dispatch</li>
 * </ol>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class SecurityFilterChainIntegrationTest {

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
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private MockMvc mockMvc;
    private Organization testOrg;
    private User adminUser;
    private User viewerUser;
    private AuthSession adminSession;
    private AuthSession viewerSession;
    private String adminJwt;
    private String viewerJwt;

    @BeforeEach
    void setUp() {
        this.mockMvc = MockMvcBuilders
                .webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        // Seed test organization
        testOrg = organizationRepository.save(Organization.create("Filter Chain Org " + UUID.randomUUID()));

        // Seed Admin user and session
        adminUser = userRepository.save(User.create("admin_" + UUID.randomUUID() + "@stockguard.io", passwordEncoder.encode("Password123!")));
        membershipRepository.save(OrganizationMembership.create(testOrg.getId(), adminUser.getId(), Role.ADMIN));
        adminSession = authSessionRepository.save(AuthSession.create(
                adminUser.getId(),
                Instant.now().plus(7, ChronoUnit.DAYS),
                "127.0.0.1",
                "SecurityTestAgent/1.0"
        ));
        adminJwt = jwtService.generateAccessToken(adminUser.getId(), adminUser.getNormalizedEmail(), adminSession.getId());

        // Seed Viewer user and session
        viewerUser = userRepository.save(User.create("viewer_" + UUID.randomUUID() + "@stockguard.io", passwordEncoder.encode("Password123!")));
        membershipRepository.save(OrganizationMembership.create(testOrg.getId(), viewerUser.getId(), Role.VIEWER));
        viewerSession = authSessionRepository.save(AuthSession.create(
                viewerUser.getId(),
                Instant.now().plus(7, ChronoUnit.DAYS),
                "127.0.0.1",
                "SecurityTestAgent/1.0"
        ));
        viewerJwt = jwtService.generateAccessToken(viewerUser.getId(), viewerUser.getNormalizedEmail(), viewerSession.getId());
    }

    @Test
    @DisplayName("1. Missing JWT returns 401 Unauthorized")
    void missingJwtReturns401() throws Exception {
        mockMvc.perform(get("/api/organizations/current/memberships")
                        .header("X-Organization-Id", testOrg.getId().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("Unauthorized"));
    }

    @Test
    @DisplayName("2. Valid JWT without organization header returns 400 Bad Request")
    void validJwtWithoutOrganizationHeaderReturns400() throws Exception {
        mockMvc.perform(get("/api/organizations/current/memberships")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Missing mandatory organization header 'X-Organization-Id'"));
    }

    @Test
    @DisplayName("3. Valid JWT with valid membership reaches the protected endpoint and returns 200 OK")
    void validJwtWithValidMembershipReachesProtectedEndpoint() throws Exception {
        mockMvc.perform(get("/api/organizations/current/memberships")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", testOrg.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(2))));
    }

    @Test
    @DisplayName("4. Valid JWT with insufficient permissions returns 403 Forbidden")
    void validJwtWithInsufficientPermissionsReturns403() throws Exception {
        // Viewer has 'membership:view', but lacks 'membership:manage' required for POST /members
        User candidate = userRepository.save(User.create("candidate_" + UUID.randomUUID() + "@stockguard.io", passwordEncoder.encode("Password123!")));
        AddMemberRequest requestBody = new AddMemberRequest(candidate.getId(), Role.OPERATOR);

        mockMvc.perform(post("/api/organizations/current/members")
                        .header("Authorization", "Bearer " + viewerJwt)
                        .header("X-Organization-Id", testOrg.getId().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(requestBody)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.error").value("Forbidden"));
    }

    @Test
    @DisplayName("5. Revoked session returns 401 Unauthorized via database-backed session validation")
    void revokedSessionReturns401() throws Exception {
        // Explicitly revoke the admin session in PostgreSQL auth_sessions table
        adminSession.revoke("Explicit test revocation", Instant.now());
        authSessionRepository.save(adminSession);

        // Attempt request with the existing JWT whose session is now revoked
        mockMvc.perform(get("/api/organizations/current/memberships")
                        .header("Authorization", "Bearer " + adminJwt)
                        .header("X-Organization-Id", testOrg.getId().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication session has been revoked"));
    }
}
