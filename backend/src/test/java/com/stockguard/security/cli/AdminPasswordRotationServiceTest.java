package com.stockguard.security.cli;

import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class AdminPasswordRotationServiceTest {

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
    private AdminPasswordRotationService rotationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final UUID targetUserId = AdminPasswordRotationService.DEFAULT_TARGET_ADMIN_USER_ID;
    private final String targetEmail = AdminPasswordRotationService.DEFAULT_TARGET_ADMIN_EMAIL;
    private final UUID targetOrgId = AdminPasswordRotationService.DEFAULT_TARGET_ORGANIZATION_ID;

    private static final String INITIAL_PASSWORD = "SyntheticInitialPassword123!";
    private static final String REPLACEMENT_PASSWORD = "SyntheticReplacementPassword456!";

    @BeforeEach
    void setUpTestData() {
        // 1. Seed or ensure target organization exists
        if (!organizationRepository.existsById(targetOrgId)) {
            Organization org = new Organization(targetOrgId, "Acme Corporation", Instant.now(), Instant.now());
            organizationRepository.save(org);
        }

        // 2. Seed or reset target admin user with initial password hash and active status
        String initialHash = passwordEncoder.encode(INITIAL_PASSWORD);
        User user = userRepository.findById(targetUserId).orElse(null);
        if (user == null) {
            user = new User(targetUserId, targetEmail, initialHash, true, Instant.now().minusSeconds(3600), Instant.now().minusSeconds(3600));
        } else {
            user.setPasswordHash(initialHash);
            user.setActive(true);
        }
        userRepository.save(user);

        // 3. Seed or reset active ADMIN membership
        OrganizationMembership membership = membershipRepository.findByOrganizationIdAndUserId(targetOrgId, targetUserId).orElse(null);
        if (membership == null) {
            membership = new OrganizationMembership(
                    UUID.randomUUID(), targetOrgId, targetUserId, Role.ADMIN, true, Instant.now().minusSeconds(3600), Instant.now().minusSeconds(3600)
            );
        } else {
            membership.setActive(true);
            membership.setRole(Role.ADMIN);
        }
        membershipRepository.save(membership);
    }

    @Test
    @DisplayName("rotateDefaultAdminPassword rotates password and confirms new hash matches and old hash fails")
    void rotateDefaultAdminPassword_success() {
        char[] newPass = REPLACEMENT_PASSWORD.toCharArray();

        AdminPasswordRotationService.RotationResult result = rotationService.rotateDefaultAdminPassword(newPass);

        assertThat(result.rowsUpdated()).isEqualTo(1);
        assertThat(result.userId()).isEqualTo(targetUserId);
        assertThat(result.email()).isEqualTo(targetEmail.toLowerCase());
        assertThat(result.organizationId()).isEqualTo(targetOrgId);

        // Fetch updated user from DB
        User updatedUser = userRepository.findById(targetUserId).orElseThrow();
        assertThat(passwordEncoder.matches(REPLACEMENT_PASSWORD, updatedUser.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(INITIAL_PASSWORD, updatedUser.getPasswordHash())).isFalse();
        assertThat(updatedUser.getUpdatedAt()).isAfterOrEqualTo(updatedUser.getCreatedAt());
    }

    @Test
    @DisplayName("rotateDefaultAdminPassword with old password verification succeeds and verifies old password invalidation")
    void rotateDefaultAdminPassword_withOldPasswordVerification_success() {
        char[] newPass = REPLACEMENT_PASSWORD.toCharArray();
        char[] oldPass = INITIAL_PASSWORD.toCharArray();

        AdminPasswordRotationService.RotationResult result =
                rotationService.rotateDefaultAdminPassword(newPass, oldPass);

        assertThat(result.rowsUpdated()).isEqualTo(1);

        User updatedUser = userRepository.findById(targetUserId).orElseThrow();
        assertThat(passwordEncoder.matches(REPLACEMENT_PASSWORD, updatedUser.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(INITIAL_PASSWORD, updatedUser.getPasswordHash())).isFalse();
    }

    @Test
    @DisplayName("rotateAdminPassword fails when new password is under 12 characters")
    void rotateAdminPassword_failsOnShortPassword() {
        char[] shortPass = "Short123!".toCharArray();

        assertThatThrownBy(() -> rotationService.rotateDefaultAdminPassword(shortPass))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 12 characters");

        // Verify password in DB was untouched
        User user = userRepository.findById(targetUserId).orElseThrow();
        assertThat(passwordEncoder.matches(INITIAL_PASSWORD, user.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("rotateAdminPassword fails when new password exceeds 72 UTF-8 bytes for BCrypt")
    void rotateAdminPassword_failsOnPasswordExceeding72Bytes() {
        char[] longPass = "A".repeat(73).toCharArray();

        assertThatThrownBy(() -> rotationService.rotateDefaultAdminPassword(longPass))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds maximum allowable length of 72 UTF-8 bytes");

        User user = userRepository.findById(targetUserId).orElseThrow();
        assertThat(passwordEncoder.matches(INITIAL_PASSWORD, user.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("rotateAdminPassword fails when target user is not found")
    void rotateAdminPassword_failsWhenUserNotFound() {
        UUID unknownUserId = UUID.randomUUID();
        char[] newPass = REPLACEMENT_PASSWORD.toCharArray();

        assertThatThrownBy(() -> rotationService.rotateAdminPassword(newPass, unknownUserId, targetEmail, targetOrgId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target user not found");
    }

    @Test
    @DisplayName("rotateAdminPassword fails when target email does not match")
    void rotateAdminPassword_failsOnEmailMismatch() {
        char[] newPass = REPLACEMENT_PASSWORD.toCharArray();

        assertThatThrownBy(() -> rotationService.rotateAdminPassword(newPass, targetUserId, "wrong-email@stockguard.dev", targetOrgId, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target user email mismatch");
    }

    @Test
    @DisplayName("rotateAdminPassword fails when target user is inactive")
    void rotateAdminPassword_failsWhenUserInactive() {
        User user = userRepository.findById(targetUserId).orElseThrow();
        user.setActive(false);
        userRepository.save(user);

        char[] newPass = REPLACEMENT_PASSWORD.toCharArray();

        assertThatThrownBy(() -> rotationService.rotateDefaultAdminPassword(newPass))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Target user is inactive");
    }

    @Test
    @DisplayName("rotateAdminPassword fails when organization membership is inactive")
    void rotateAdminPassword_failsWhenMembershipInactive() {
        OrganizationMembership membership = membershipRepository.findByOrganizationIdAndUserId(targetOrgId, targetUserId).orElseThrow();
        membership.setActive(false);
        membershipRepository.save(membership);

        char[] newPass = REPLACEMENT_PASSWORD.toCharArray();

        assertThatThrownBy(() -> rotationService.rotateDefaultAdminPassword(newPass))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("membership in organization")
                .hasMessageContaining("is inactive");
    }

    @Test
    @DisplayName("rotateAdminPassword fails when organization membership role is not ADMIN")
    void rotateAdminPassword_failsWhenRoleNotAdmin() {
        OrganizationMembership membership = membershipRepository.findByOrganizationIdAndUserId(targetOrgId, targetUserId).orElseThrow();
        membership.setRole(Role.VIEWER);
        membershipRepository.save(membership);

        char[] newPass = REPLACEMENT_PASSWORD.toCharArray();

        assertThatThrownBy(() -> rotationService.rotateDefaultAdminPassword(newPass))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not hold ADMIN role");
    }

    @Test
    @DisplayName("rotateAdminPassword fails when new password is identical to current password")
    void rotateAdminPassword_failsWhenNewPasswordMatchesCurrent() {
        char[] identicalPass = INITIAL_PASSWORD.toCharArray();

        assertThatThrownBy(() -> rotationService.rotateDefaultAdminPassword(identicalPass))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be identical to current password");
    }

    @Test
    @DisplayName("rotateAdminPassword fails and rolls back when old password verification unexpectedly matches new hash")
    void rotateAdminPassword_failsAndRollsBackOnVerificationFailure() {
        // If caller passes the new password as the "old password verification", post-rotation check detects it matches and rolls back
        char[] newPass = REPLACEMENT_PASSWORD.toCharArray();
        char[] fakeOldPassMatchingNew = REPLACEMENT_PASSWORD.toCharArray();

        assertThatThrownBy(() -> rotationService.rotateDefaultAdminPassword(newPass, fakeOldPassMatchingNew))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Post-rotation verification failed");

        // Verify transaction rolled back: database still retains initial password
        User user = userRepository.findById(targetUserId).orElseThrow();
        assertThat(passwordEncoder.matches(INITIAL_PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(REPLACEMENT_PASSWORD, user.getPasswordHash())).isFalse();
    }
}
