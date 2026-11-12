package com.stockguard.security.bootstrap;

import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.security.config.SecurityProperties;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
    private AdminBootstrapRunner bootstrapRunner;

    @Test
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
    @DisplayName("bootstrap provisions admin user, org, membership, and audit log on pristine DB")
    void bootstrapProvisionsAdminEntities() throws Exception {
        SecurityProperties.Bootstrap config = securityProperties.getBootstrap();
        config.setEnabled(true);
        config.setAdminEmail("root-admin@stockguard.io");
        config.setAdminPassword("BootstrapSecret123!");
        config.setAdminFullName("System Administrator");
        config.setOrgName("StockGuard Primary Tenant");

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

        // Verify Audit Log
        List<Map<String, Object>> auditEntries = jdbcTemplate.queryForList(
                "SELECT * FROM audit_logs WHERE entity_id = ? AND action = 'SYSTEM_BOOTSTRAP'",
                user.getId()
        );
        assertThat(auditEntries).isNotEmpty();
        Map<String, Object> log = auditEntries.getFirst();
        assertThat(log.get("actor_user_id")).isEqualTo(user.getId());
        assertThat(log.get("entity_type")).isEqualTo("SYSTEM");

        // Running again must be idempotent
        bootstrapRunner.run(null);
        assertThat(userRepository.findByNormalizedEmail("root-admin@stockguard.io")).isPresent();
    }

    @Test
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
        userRepository.save(orphan);

        assertThatThrownBy(() -> bootstrapRunner.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inconsistent database state");
    }
}
