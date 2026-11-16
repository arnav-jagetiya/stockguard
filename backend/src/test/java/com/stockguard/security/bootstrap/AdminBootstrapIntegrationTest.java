package com.stockguard.security.bootstrap;

import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.security.config.SecurityProperties;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.JdbcRefreshTokenOperations;
import com.stockguard.security.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminBootstrapIntegrationTest {

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
        // Start disabled so we can test runner invocations with controlled state
        registry.add("stockguard.security.bootstrap.enabled", () -> "false");
    }

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SecurityProperties securityProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JdbcRefreshTokenOperations jdbcOperations;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AdminBootstrapRunner bootstrapRunner;

    @Test
    @Order(1)
    @DisplayName("bootstrap does nothing when disabled")
    void bootstrapDisabledDoesNothing() throws Exception {
        SecurityProperties.Bootstrap config = securityProperties.getBootstrap();
        config.setEnabled(false);

        long userCountBefore = userRepository.count();
        long orgCountBefore = organizationRepository.count();

        bootstrapRunner.run(null);

        assertThat(userRepository.count()).isEqualTo(userCountBefore);
        assertThat(organizationRepository.count()).isEqualTo(orgCountBefore);
    }

    @Test
    @Order(2)
    @DisplayName("bootstrap fails closed if enabled but credentials incomplete")
    void bootstrapFailsClosedOnIncompleteCredentials() {
        SecurityProperties.Bootstrap config = securityProperties.getBootstrap();
        config.setEnabled(true);
        config.setAdminEmail("admin@stockguard.io");
        config.setAdminPassword(""); // blank
        config.setAdminFullName("System Administrator");
        config.setOrgName("Initial Org");

        assertThatThrownBy(() -> bootstrapRunner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bootstrap admin-password must be at least 8 characters");
    }

    @Test
    @Order(3)
    @DisplayName("bootstrap fails closed if admin exists without corresponding organization membership")
    void bootstrapFailsClosedOnPartialInconsistentState() {
        SecurityProperties.Bootstrap config = securityProperties.getBootstrap();
        config.setEnabled(true);
        config.setAdminEmail("orphan-admin@stockguard.io");
        config.setAdminPassword("ValidPassword123!");
        config.setAdminFullName("Orphan Admin");
        config.setOrgName("Orphan Corp");

        // Manually create orphan user without organization or membership
        User orphan = User.create("orphan-admin@stockguard.io", passwordEncoder.encode("ValidPassword123!"));
        orphan = userRepository.saveAndFlush(orphan);

        try {
            assertThatThrownBy(() -> bootstrapRunner.run(null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Partially initialized database detected");
        } finally {
            // Clean up orphan user so subsequent tests start with clean DB
            userRepository.delete(orphan);
            userRepository.flush();
        }
    }

    @Test
    @Order(4)
    @DisplayName("bootstrap provisions admin entities and handles recovery lifecycle")
    void bootstrapProvisionsAndHandlesRecoveryLifecycle() throws Exception {
        SecurityProperties.Bootstrap config = securityProperties.getBootstrap();
        config.setEnabled(true);
        config.setAdminEmail("root-admin@stockguard.io");
        config.setAdminPassword("BootstrapSecret123!");
        config.setAdminFullName("System Administrator");
        config.setOrgName("StockGuard Primary Tenant");
        config.setAllowPasswordReset(false);

        // 1. Pristine bootstrap
        bootstrapRunner.run(null);

        // Verify User
        User user = userRepository.findByNormalizedEmail("root-admin@stockguard.io").orElseThrow();
        assertThat(user.isActive()).isTrue();
        assertThat(passwordEncoder.matches("BootstrapSecret123!", user.getPasswordHash())).isTrue();

        // Verify Organization
        Organization org = organizationRepository.findAll().stream()
                .filter(o -> "StockGuard Primary Tenant".equals(o.getName()))
                .findFirst()
                .orElseThrow();

        // Verify Membership
        List<OrganizationMembership> memberships = membershipRepository.findAllByUserIdAndActiveTrue(user.getId());
        assertThat(memberships).hasSize(1);
        OrganizationMembership membership = memberships.getFirst();
        assertThat(membership.getOrganizationId()).isEqualTo(org.getId());
        assertThat(membership.getRole()).isEqualTo(Role.ADMIN);
        assertThat(membership.isActive()).isTrue();

        // Verify Initial Audit Log
        List<Map<String, Object>> initialAudit = jdbcTemplate.queryForList(
                "SELECT * FROM audit_logs WHERE entity_id = ? AND action = 'SYSTEM_BOOTSTRAP'",
                user.getId()
        );
        assertThat(initialAudit).isNotEmpty();
        Map<String, Object> log = initialAudit.getFirst();
        assertThat(log.get("actor_user_id")).isEqualTo(user.getId());
        assertThat(log.get("entity_type")).isEqualTo("SYSTEM");

        // 2. Running again with allowPasswordReset=false and different password is idempotent (does NOT change password)
        config.setAdminPassword("IgnoredPassword789!");
        bootstrapRunner.run(null);
        User userUnchanged = userRepository.findByNormalizedEmail("root-admin@stockguard.io").orElseThrow();
        assertThat(passwordEncoder.matches("BootstrapSecret123!", userUnchanged.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("IgnoredPassword789!", userUnchanged.getPasswordHash())).isFalse();

        // 3. Running with allowPasswordReset=true updates password and writes recovery audit log
        config.setAllowPasswordReset(true);
        config.setAdminPassword("RecoveredPassword456!");
        bootstrapRunner.run(null);

        User userUpdated = userRepository.findByNormalizedEmail("root-admin@stockguard.io").orElseThrow();
        assertThat(passwordEncoder.matches("RecoveredPassword456!", userUpdated.getPasswordHash())).isTrue();

        List<Map<String, Object>> recoveryAudit = jdbcTemplate.queryForList(
                "SELECT * FROM audit_logs WHERE entity_id = ? AND action = 'ADMIN_PASSWORD_RESET_RECOVERY'",
                userUpdated.getId()
        );
        assertThat(recoveryAudit).isNotEmpty();

        // 4. Running with allowPasswordReset=true in prod profile fails closed
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");

        AdminBootstrapRunner prodRunner = new AdminBootstrapRunner(
                securityProperties,
                userRepository,
                organizationRepository,
                membershipRepository,
                passwordEncoder,
                jdbcOperations,
                jdbcTemplate,
                transactionManager,
                prodEnv
        );

        config.setAdminPassword("AttemptedProdPassword!");
        assertThatThrownBy(() -> prodRunner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bootstrap password reset is strictly forbidden in production");
    }
}
