package com.stockguard.infrastructure;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("dev")
class ObservabilitySecurityIntegrationTest {

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

    private MockMvc mockMvc;
    private String adminJwt;
    private UUID orgId;

    private static final String SCRAPER_USER = "stockguard-prometheus";
    private static final String SCRAPER_PASS = "dev-scrape-pass";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        Organization org = organizationRepository.save(Organization.create("Obs Org " + UUID.randomUUID()));
        orgId = org.getId();

        User adminUser = userRepository.save(User.create("obs.admin." + UUID.randomUUID() + "@stockguard.com", "hash"));
        membershipRepository.save(OrganizationMembership.create(orgId, adminUser.getId(), Role.ADMIN));
        AuthSession adminSession = sessionRepository.save(AuthSession.create(adminUser.getId(), Instant.now().plus(1, ChronoUnit.HOURS), "127.0.0.1", "JUnit"));
        adminJwt = jwtService.generateAccessToken(adminUser.getId(), adminUser.getEmail(), adminSession.getId());
    }

    private String basicAuth(String username, String password) {
        String token = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8));
    }

    // =========================================================================
    // 1. PROMETHEUS SCRAPER SECURITY & AUTHENTICATION
    // =========================================================================

    @Test
    @DisplayName("GET /actuator/prometheus without authentication returns 401 Unauthorized")
    void prometheusWithoutAuthReturns401() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /actuator/prometheus with invalid Basic Auth returns 401 Unauthorized")
    void prometheusWithInvalidCredentialsReturns401() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", basicAuth(SCRAPER_USER, "wrong-password")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /actuator/prometheus with business user JWT (even ADMIN) returns 403 Forbidden")
    void prometheusWithBusinessJwtReturns403() throws Exception {
        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", "Bearer " + adminJwt))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /actuator/prometheus with valid scraper HTTP Basic Auth returns 200 OK and custom metrics")
    void prometheusWithValidScraperBasicAuthReturns200AndMetrics() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", basicAuth(SCRAPER_USER, SCRAPER_PASS)))
                .andExpect(status().isOk())
                .andReturn();

        String content = result.getResponse().getContentAsString();
        assertThat(content).isNotBlank();

        // Verify core standardized metrics from ADR-014
        assertThat(content).contains("stockguard_reconciliation_executions_total");
        assertThat(content).contains("stockguard_reconciliation_discrepancies_active");
        assertThat(content).contains("stockguard_reservations_total");
        assertThat(content).contains("stockguard_reservations_expired_total");
        assertThat(content).contains("stockguard_reservations_released_total");
        assertThat(content).contains("stockguard_reservations_consumed_total");
        assertThat(content).contains("stockguard_auth_failures_total");
        assertThat(content).contains("stockguard_db_deadlock_retries_total");
    }

    @Test
    @DisplayName("H-1 Regression: Consecutive Prometheus scrapes on the same application context succeed without credential erasure failure")
    void consecutivePrometheusScrapesSucceedWithoutCredentialErasureFailure() throws Exception {
        // Assertion 1: First authenticated /actuator/prometheus request succeeds
        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", basicAuth(SCRAPER_USER, SCRAPER_PASS)))
                .andExpect(status().isOk());

        // Assertion 2: Second authenticated request succeeds (verifies credential erasure did not mutate UserDetails)
        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", basicAuth(SCRAPER_USER, SCRAPER_PASS)))
                .andExpect(status().isOk());

        // Assertion 3: At least five consecutive authenticated scrape requests succeed (testing total 6 consecutive)
        for (int i = 3; i <= 6; i++) {
            MvcResult result = mockMvc.perform(get("/actuator/prometheus")
                            .header("Authorization", basicAuth(SCRAPER_USER, SCRAPER_PASS)))
                    .andExpect(status().isOk())
                    .andReturn();

            String content = result.getResponse().getContentAsString();
            assertThat(content)
                    .as("Scrape request %d must return valid metrics", i)
                    .isNotBlank()
                    .contains("stockguard_reconciliation_executions_total");
        }

        // Assertion 5: Incorrect scraper credentials are rejected
        mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", basicAuth(SCRAPER_USER, "wrong-password")))
                .andExpect(status().isUnauthorized());

        // Assertion 6: Requests without credentials are rejected
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());

        // Assertion 4: Authentication continues working after credential erasure and failed attempts
        MvcResult finalResult = mockMvc.perform(get("/actuator/prometheus")
                        .header("Authorization", basicAuth(SCRAPER_USER, SCRAPER_PASS)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(finalResult.getResponse().getContentAsString()).isNotBlank();
    }

    // =========================================================================
    // 2. PUBLIC OPERATIONAL HEALTH AND READINESS PROBES
    // =========================================================================

    @Test
    @DisplayName("GET /actuator/health is accessible without authentication (.permitAll)")
    void healthProbePermitAll() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /actuator/health/liveness is accessible without authentication (.permitAll)")
    void livenessProbePermitAll() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /actuator/health/readiness is accessible without authentication (.permitAll)")
    void readinessProbePermitAll() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /actuator/info is accessible without authentication (.permitAll)")
    void infoEndpointPermitAll() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // 3. PRIVILEGE SEPARATION: SCRAPER CANNOT ACCESS BUSINESS APIS
    // =========================================================================

    @Test
    @DisplayName("Scraper account attempting to access business API /api/** is rejected (privilege separation)")
    void scraperCannotAccessBusinessApis() throws Exception {
        MvcResult res = mockMvc.perform(get("/api/dashboard/summary")
                        .header("Authorization", basicAuth(SCRAPER_USER, SCRAPER_PASS))
                        .header("X-Organization-Id", orgId.toString()))
                .andReturn();
        assertThat(res.getResponse().getStatus()).isIn(401, 403);
    }
}
