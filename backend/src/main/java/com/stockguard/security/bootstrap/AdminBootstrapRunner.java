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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Opt-in, deterministic, idempotent administrator bootstrap runner.
 * <p>
 * Activates exclusively when {@code stockguard.security.bootstrap.enabled=true}.
 * Coordinates multi-instance startup using a PostgreSQL transaction-scoped advisory lock.
 * Seeds initial organization, administrator identity (BCrypt hashed), and ADMIN membership transactionally.
 * Fails closed if configurations are missing or database state is partially initialized / inconsistent.
 */
@Component
@Order(10)
@org.springframework.context.annotation.Profile("!test")
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);
    private static final java.security.SecureRandom SECURE_RANDOM = new java.security.SecureRandom();
    public static final long BOOTSTRAP_ADVISORY_LOCK_KEY = 0x53544F434B475541L; // 'STOCKGUA'

    /**
     * Generates an unguessable 256-bit cryptographically secure random credential (64 hex characters).
     * Complies strictly with BCrypt's 72-byte ceiling (64 bytes in ASCII/UTF-8).
     */
    public static String generateSecure256BitHexCredential() {
        byte[] randomBytes = new byte[32]; // 32 bytes = 256 bits of cryptographic entropy
        SECURE_RANDOM.nextBytes(randomBytes);
        return java.util.HexFormat.of().formatHex(randomBytes);
    }

    private final SecurityProperties securityProperties;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcRefreshTokenOperations jdbcOperations;
    private final JdbcTemplate jdbcTemplate;
    private final PlatformTransactionManager transactionManager;
    private final Environment environment;

    public AdminBootstrapRunner(
            SecurityProperties securityProperties,
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            OrganizationMembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            JdbcRefreshTokenOperations jdbcOperations,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            Environment environment
    ) {
        this.securityProperties = securityProperties;
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcOperations = jdbcOperations;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionManager = transactionManager;
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        SecurityProperties.Bootstrap config = securityProperties.getBootstrap();
        if (config == null || !config.isEnabled()) {
            log.debug("Initial administrator bootstrap is disabled by configuration.");
            return;
        }

        validateConfiguration(config);

        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setName("adminBootstrapRunnerTransaction");
        TransactionStatus status = transactionManager.getTransaction(def);

        try {
            // Coordinate multi-node startup with PostgreSQL transaction advisory lock
            boolean lockAcquired = jdbcOperations.tryAdvisoryXactLock(BOOTSTRAP_ADVISORY_LOCK_KEY);
            if (!lockAcquired) {
                throw new IllegalStateException(
                        "Failed to acquire PostgreSQL advisory lock for administrator bootstrap. Another instance may be bootstrapping.");
            }

            executeBootstrap(config);

            transactionManager.commit(status);
        } catch (Exception ex) {
            if (!status.isCompleted()) {
                transactionManager.rollback(status);
            }
            log.error("Administrator bootstrap failed: {}", ex.getMessage());
            throw ex;
        }
    }

    private void validateConfiguration(SecurityProperties.Bootstrap config) {
        if (config.isAllowPasswordReset() && environment != null && environment.matchesProfiles("prod")) {
            throw new IllegalStateException(
                    "Bootstrap password reset is strictly forbidden in production (prod profile active).");
        }
        if (config.getAdminEmail() == null || config.getAdminEmail().isBlank()) {
            throw new IllegalStateException("Bootstrap admin-email must not be blank when bootstrap is enabled");
        }
        if (config.getAdminPassword() != null && !config.getAdminPassword().isBlank() && config.getAdminPassword().length() < 8) {
            throw new IllegalStateException("Bootstrap admin-password must be at least 8 characters when configured");
        }
        if ((config.getAdminPassword() == null || config.getAdminPassword().isBlank()) && environment != null && environment.matchesProfiles("prod")) {
            throw new IllegalStateException("Bootstrap admin-password must be explicitly configured when bootstrap is enabled in production");
        }
        if (config.getAdminFullName() == null || config.getAdminFullName().isBlank()) {
            throw new IllegalStateException("Bootstrap admin-full-name must not be blank when bootstrap is enabled");
        }
        if (config.getOrgName() == null || config.getOrgName().isBlank()) {
            throw new IllegalStateException("Bootstrap org-name must not be blank when bootstrap is enabled");
        }
    }

    private void executeBootstrap(SecurityProperties.Bootstrap config) {
        String normalizedEmail = User.normalizeEmail(config.getAdminEmail());
        Optional<User> existingUserOpt = userRepository.findByNormalizedEmail(normalizedEmail);

        long userCount = userRepository.count();
        long orgCount = organizationRepository.count();

        // Check if admin user already exists
        if (existingUserOpt.isPresent()) {
            User user = existingUserOpt.get();

            // Locate active ADMIN membership
            Optional<OrganizationMembership> adminMembershipOpt = membershipRepository
                    .findAllByUserIdAndActiveTrue(user.getId())
                    .stream()
                    .filter(m -> m.getRole() == Role.ADMIN)
                    .findFirst();

            if (adminMembershipOpt.isPresent() && user.isActive()) {
                OrganizationMembership membership = adminMembershipOpt.get();
                Organization org = organizationRepository.findById(membership.getOrganizationId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Corrupt state: Admin membership references non-existent organization " + membership.getOrganizationId()));

                // Password recovery path: only if allow-password-reset=true and NOT in production
                if (config.isAllowPasswordReset()) {
                    if (environment != null && environment.matchesProfiles("prod")) {
                        throw new IllegalStateException(
                                "Bootstrap password reset is strictly forbidden in production (prod profile active).");
                    }

                    if (!passwordEncoder.matches(config.getAdminPassword(), user.getPasswordHash())) {
                        String newHashedPassword = passwordEncoder.encode(config.getAdminPassword());
                        user.setPasswordHash(newHashedPassword);
                        userRepository.saveAndFlush(user);

                        insertPasswordResetAuditLog(org.getId(), user.getId(), Instant.now());

                        log.info("Administrator bootstrap: Successfully reset password for administrator user '{}' in organization '{}' (id: {}) under recovery configuration.",
                                user.getNormalizedEmail(), org.getName(), org.getId());
                        return;
                    } else {
                        log.info("Administrator bootstrap: Administrator user '{}' in organization '{}' already matches configured recovery password. Skipping.",
                                user.getNormalizedEmail(), org.getName());
                        return;
                    }
                }

                log.info("Administrator bootstrap: Administrator user '{}' and organization '{}' are already fully initialized. Skipping.",
                        user.getNormalizedEmail(), org.getName());
                return;
            }

            throw new IllegalStateException(
                    "Partially initialized database detected: User exists, but valid active ADMIN membership is missing.");
        }

        // Partial initialization fail-closed checks
        if (userCount > 0 || orgCount > 0) {
            throw new IllegalStateException(
                    "Partially initialized or inconsistent database state detected: userCount=" + userCount +
                            ", orgCount=" + orgCount +
                            ", userExists=false. Cannot safely bootstrap administrator.");
        }

        // Pristine initialization
        Instant now = Instant.now();

        Organization org = Organization.create(config.getOrgName().trim());
        org = organizationRepository.saveAndFlush(org);

        String effectivePassword;
        if (config.getAdminPassword() != null && !config.getAdminPassword().isBlank()) {
            effectivePassword = config.getAdminPassword();
            log.info("Administrator bootstrap: Using explicitly configured credential for '{}'.", config.getAdminEmail());
        } else {
            effectivePassword = generateSecure256BitHexCredential();
            log.warn("Administrator bootstrap: No password configured for '{}'; generated unguessable 256-bit random credential.", config.getAdminEmail());
        }

        String hashedPassword = passwordEncoder.encode(effectivePassword);
        User user = User.create(config.getAdminEmail(), hashedPassword);
        user = userRepository.saveAndFlush(user);

        OrganizationMembership membership = OrganizationMembership.create(org.getId(), user.getId(), Role.ADMIN);
        membershipRepository.saveAndFlush(membership);

        // Record bootstrap in immutable audit log
        insertBootstrapAuditLog(org.getId(), user.getId(), now);

        log.info("Administrator bootstrap completed successfully: Created organization '{}' (id: {}) and admin user '{}' (id: {}).",
                org.getName(), org.getId(), user.getNormalizedEmail(), user.getId());
    }

    private void insertPasswordResetAuditLog(UUID orgId, UUID userId, Instant now) {
        String sql = "INSERT INTO audit_logs " +
                "(id, organization_id, actor_user_id, action, entity_type, entity_id, metadata, created_at) " +
                "VALUES (?, ?, ?, 'ADMIN_PASSWORD_RESET_RECOVERY', 'USER', ?, ?::jsonb, ?)";
        String metadataJson = "{\"event\": \"ADMIN_PASSWORD_RESET_RECOVERY\", \"recovered_role\": \"ADMIN\"}";
        jdbcTemplate.update(
                sql,
                UUID.randomUUID(),
                orgId,
                userId,
                userId,
                metadataJson,
                Timestamp.from(now)
        );
    }

    private void insertBootstrapAuditLog(UUID orgId, UUID userId, Instant now) {
        String sql = "INSERT INTO audit_logs " +
                "(id, organization_id, actor_user_id, action, entity_type, entity_id, metadata, created_at) " +
                "VALUES (?, ?, ?, 'SYSTEM_BOOTSTRAP', 'SYSTEM', ?, ?::jsonb, ?)";
        String metadataJson = "{\"event\": \"INITIAL_ADMIN_BOOTSTRAP\", \"role\": \"ADMIN\"}";
        jdbcTemplate.update(
                sql,
                UUID.randomUUID(),
                orgId,
                userId,
                userId,
                metadataJson,
                Timestamp.from(now)
        );
    }
}
