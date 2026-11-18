package com.stockguard.demo;

import com.stockguard.demo.config.DemoProperties;
import com.stockguard.demo.dto.DemoSeedResult;
import com.stockguard.demo.service.DemoEnvironmentSeeder;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 12B Security Regression Test Suite.
 * Validates credential sanitization, secure random fallbacks, non-overwriting idempotency,
 * and visitor RBAC assignment.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class DemoSecurityHardeningTest {

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
        registry.add("stockguard.security.jwt.active-key.secret", () -> "vWvE+H3M7P+T0GfQx4zL+cK9p9V5hW8J2eT4rY6uI0A=");
    }

    @Autowired
    private DemoEnvironmentSeeder demoEnvironmentSeeder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Test
    @DisplayName("DemoProperties defaults to null adminPassword with no hardcoded fallback")
    void demoPropertiesDefaultAdminPasswordIsNull() {
        DemoProperties properties = new DemoProperties();
        assertThat(properties.getAdminPassword()).isNull();
    }

    @Test
    @DisplayName("Missing administrator credential generates random unguessable password and does not match historical password")
    void missingAdminCredentialGeneratesRandomUnguessablePassword() {
        DemoProperties properties = new DemoProperties();
        properties.setSeedEnabled(true);
        properties.setOrgName("Secured Demo Tenant Alpha");
        String uniqueAdminEmail = "admin-random-" + UUID.randomUUID() + "@stockguard.dev";
        properties.setAdminEmail(uniqueAdminEmail);
        // Explicitly leave adminPassword null (or empty)
        properties.setAdminPassword(null);

        DemoSeedResult result = demoEnvironmentSeeder.seedDemoEnvironment(properties);
        assertThat(result.organizationId()).isNotNull();

        Optional<User> adminOpt = userRepository.findByNormalizedEmail(User.normalizeEmail(uniqueAdminEmail));
        assertThat(adminOpt).isPresent();
        User admin = adminOpt.get();

        // Must NOT match predictable default strings
        assertThat(passwordEncoder.matches("", admin.getPasswordHash())).isFalse();
        assertThat(passwordEncoder.matches("password", admin.getPasswordHash())).isFalse();
        assertThat(passwordEncoder.matches("admin", admin.getPasswordHash())).isFalse();
        assertThat(passwordEncoder.matches("Admin123!", admin.getPasswordHash())).isFalse();
        assertThat(passwordEncoder.matches(uniqueAdminEmail, admin.getPasswordHash())).isFalse();

        // Must be a valid BCrypt hash
        assertThat(admin.getPasswordHash()).startsWith("$2a$").hasSize(60);
    }

    @Test
    @DisplayName("Existing administrator credentials in database are never overwritten on subsequent seeder runs")
    void existingAdministratorCredentialsArePreservedOnRestart() {
        String existingEmail = "admin-preserve-" + UUID.randomUUID() + "@stockguard.dev";
        String initialPassword = "InitialCustomPassword2026#Secure";
        User existingUser = User.create(existingEmail, passwordEncoder.encode(initialPassword));
        existingUser = userRepository.saveAndFlush(existingUser);
        String originalPasswordHash = existingUser.getPasswordHash();

        DemoProperties properties = new DemoProperties();
        properties.setSeedEnabled(true);
        properties.setOrgName("Secured Demo Tenant Beta");
        properties.setAdminEmail(existingEmail);
        // Even if seeder is called with a different configured password:
        properties.setAdminPassword("DifferentAttemptedPassword999!");

        DemoSeedResult result = demoEnvironmentSeeder.seedDemoEnvironment(properties);
        assertThat(result.organizationId()).isNotNull();

        // Verify the user in the database strictly retained the original password hash
        User reloadedUser = userRepository.findByNormalizedEmail(User.normalizeEmail(existingEmail)).orElseThrow();
        assertThat(reloadedUser.getPasswordHash()).isEqualTo(originalPasswordHash);
        assertThat(passwordEncoder.matches(initialPassword, reloadedUser.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("DifferentAttemptedPassword999!", reloadedUser.getPasswordHash())).isFalse();
    }

    @Test
    @DisplayName("Explicitly configured administrator password is used when provided")
    void explicitlyConfiguredCredentialsHandledSecurely() {
        DemoProperties properties = new DemoProperties();
        properties.setSeedEnabled(true);
        properties.setOrgName("Secured Demo Tenant Gamma");
        String uniqueAdminEmail = "admin-explicit-" + UUID.randomUUID() + "@stockguard.dev";
        properties.setAdminEmail(uniqueAdminEmail);
        String explicitPassword = "ExplicitConfiguredPass2026!#Valid";
        properties.setAdminPassword(explicitPassword);

        DemoSeedResult result = demoEnvironmentSeeder.seedDemoEnvironment(properties);
        assertThat(result.organizationId()).isNotNull();

        User admin = userRepository.findByNormalizedEmail(User.normalizeEmail(uniqueAdminEmail)).orElseThrow();
        assertThat(passwordEncoder.matches(explicitPassword, admin.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("DifferentPassword123!", admin.getPasswordHash())).isFalse();
    }

    @Test
    @DisplayName("Public demo visitor receives VIEWER role and has randomized credential")
    void demoVisitorReceivesViewerRoleAndRandomizedCredential() {
        DemoProperties properties = new DemoProperties();
        properties.setSeedEnabled(true);
        properties.setOrgName("Secured Demo Tenant Delta");
        properties.setAdminEmail("admin-delta-" + UUID.randomUUID() + "@stockguard.dev");

        DemoSeedResult result = demoEnvironmentSeeder.seedDemoEnvironment(properties);
        UUID orgId = result.organizationId();

        User visitor = userRepository.findByNormalizedEmail(User.normalizeEmail("demo.visitor@stockguard.dev")).orElseThrow();

        // Must NOT match predictable default strings
        assertThat(passwordEncoder.matches("demo", visitor.getPasswordHash())).isFalse();
        assertThat(passwordEncoder.matches("visitor", visitor.getPasswordHash())).isFalse();
        assertThat(passwordEncoder.matches("password", visitor.getPasswordHash())).isFalse();
        assertThat(visitor.getPasswordHash()).startsWith("$2a$").hasSize(60);

        // Must have VIEWER membership in the seeded organization
        Optional<OrganizationMembership> orgMembership = membershipRepository.findByOrganizationIdAndUserId(orgId, visitor.getId());

        assertThat(orgMembership).isPresent();
        assertThat(orgMembership.get().getRole()).isEqualTo(Role.VIEWER);
        assertThat(orgMembership.get().isActive()).isTrue();
    }

    @Test
    @DisplayName("SecureRandom credential generation produces 64-char hex string with full 256-bit entropy under BCrypt 72-byte ceiling")
    void secureRandomCredentialMeets256BitHexSpecifications() {
        String cred1 = DemoEnvironmentSeeder.generateSecure256BitHexCredential();
        String cred2 = DemoEnvironmentSeeder.generateSecure256BitHexCredential();

        assertThat(cred1)
                .isNotNull()
                .hasSize(64)
                .matches("^[0-9a-f]{64}$")
                .isNotEqualTo(cred2);

        // Strictly verify byte length respects BCrypt's 72-byte ceiling
        assertThat(cred1.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isEqualTo(64)
                .isLessThanOrEqualTo(72);
    }
}
