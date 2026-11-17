package com.stockguard.security.bootstrap;

import com.stockguard.audit.service.AuditLogService;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.security.config.SecurityProperties;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Opt-in, deterministic, idempotent bootstrap provisioner for the public demo visitor identity.
 * <p>
 * Activates when {@code stockguard.security.public-demo.enabled=true}.
 * Ensures {@code demo.visitor@stockguard.dev} exists with an unguessable/unusable password hash
 * and holds an active {@link Role#VIEWER} membership in the demo organization.
 */
@Component
@Order(25)
@Profile("!test")
public class DemoVisitorProvisioner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoVisitorProvisioner.class);

    private final SecurityProperties securityProperties;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    @Autowired
    public DemoVisitorProvisioner(
            SecurityProperties securityProperties,
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            OrganizationMembershipRepository membershipRepository,
            PasswordEncoder passwordEncoder,
            @Autowired(required = false) AuditLogService auditLogService
    ) {
        this.securityProperties = securityProperties;
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (securityProperties == null || securityProperties.getPublicDemo() == null
                || !securityProperties.getPublicDemo().isEnabled()) {
            log.debug("Public demo visitor provisioning is disabled by configuration.");
            return;
        }

        provisionDemoVisitor();
    }

    @Transactional
    public void provisionDemoVisitor() {
        SecurityProperties.PublicDemo config = securityProperties.getPublicDemo();
        String demoEmail = config.getDemoEmail() != null ? config.getDemoEmail().trim() : "demo.visitor@stockguard.dev";
        String orgName = config.getDemoOrgName() != null ? config.getDemoOrgName().trim() : "Acme Industrial Supply Pvt. Ltd.";

        Optional<Organization> orgOpt = organizationRepository.findByName(orgName);
        if (orgOpt.isEmpty()) {
            // Fallback: check if any organization exists
            orgOpt = organizationRepository.findAll().stream().findFirst();
            if (orgOpt.isEmpty()) {
                log.warn("Cannot provision demo visitor: no organization found matching '{}' and database has zero organizations.", orgName);
                return;
            }
        }

        Organization org = orgOpt.get();
        String normalizedEmail = User.normalizeEmail(demoEmail);

        // 1. Ensure User exists with unusable random BCrypt password hash
        User user = userRepository.findByNormalizedEmail(normalizedEmail).orElseGet(() -> {
            String randomPassword = UUID.randomUUID().toString() + UUID.randomUUID().toString();
            String unusableHash = passwordEncoder.encode(randomPassword);
            User newUser = User.create(demoEmail, unusableHash);
            log.info("Created dedicated public demo visitor user: {}", normalizedEmail);
            return userRepository.saveAndFlush(newUser);
        });

        if (!user.isActive()) {
            user.setActive(true);
            user = userRepository.saveAndFlush(user);
        }

        // 2. Ensure OrganizationMembership exists with Role.VIEWER
        Optional<OrganizationMembership> membershipOpt =
                membershipRepository.findByOrganizationIdAndUserId(org.getId(), user.getId());

        if (membershipOpt.isEmpty()) {
            OrganizationMembership membership = OrganizationMembership.create(org.getId(), user.getId(), Role.VIEWER);
            membershipRepository.saveAndFlush(membership);

            if (auditLogService != null) {
                try {
                    auditLogService.logMemberAdded(org.getId(), user.getId(), user.getId(), Role.VIEWER.name(), false);
                } catch (Exception ex) {
                    log.warn("Failed to audit demo visitor member provisioning: {}", ex.getMessage());
                }
            }

            log.info("Provisioned public demo visitor membership: user '{}' -> org '{}' ({}) with role VIEWER",
                    demoEmail, org.getName(), org.getId());
        } else {
            OrganizationMembership existing = membershipOpt.get();
            if (!existing.isActive() || existing.getRole() != Role.VIEWER) {
                existing.setActive(true);
                existing.setRole(Role.VIEWER);
                membershipRepository.saveAndFlush(existing);
                log.info("Updated existing public demo visitor membership to active VIEWER: user '{}' -> org '{}'",
                        demoEmail, org.getName());
            } else {
                log.debug("Public demo visitor membership is already active with role VIEWER for org '{}'", org.getName());
            }
        }
    }
}
