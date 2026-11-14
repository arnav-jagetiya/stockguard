package com.stockguard.organization.service;

import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.dto.OrganizationMemberResponse;
import com.stockguard.organization.exception.CannotDemoteLastAdminException;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.security.context.OrganizationContext;
import com.stockguard.security.context.OrganizationContextHolder;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.security.service.TenantMembershipValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class OrganizationRbacAndLastAdminIntegrationTest {

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
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private OrganizationMembershipService membershipService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TenantMembershipValidator tenantMembershipValidator;

    private Organization testOrg;
    private User admin1;
    private User admin2;
    private User operator1;

    @BeforeEach
    void setUp() {
        testOrg = organizationRepository.save(Organization.create("Integration Test Org " + UUID.randomUUID()));

        admin1 = createUser("admin1_" + UUID.randomUUID() + "@stockguard.io");
        admin2 = createUser("admin2_" + UUID.randomUUID() + "@stockguard.io");
        operator1 = createUser("operator1_" + UUID.randomUUID() + "@stockguard.io");

        membershipRepository.save(OrganizationMembership.create(testOrg.getId(), admin1.getId(), Role.ADMIN));
        membershipRepository.save(OrganizationMembership.create(testOrg.getId(), admin2.getId(), Role.ADMIN));
        membershipRepository.save(OrganizationMembership.create(testOrg.getId(), operator1.getId(), Role.OPERATOR));

        OrganizationContextHolder.setContext(new OrganizationContext(
                testOrg.getId(), admin1.getId(), Role.ADMIN, Set.of("membership:manage")
        ));
    }

    @AfterEach
    void tearDown() {
        OrganizationContextHolder.clearContext();
    }

    private User createUser(String email) {
        User user = User.create(email, passwordEncoder.encode("Password123!"));
        return userRepository.save(user);
    }

    @Test
    @DisplayName("Sole remaining admin cannot be demoted (throws CannotDemoteLastAdminException)")
    void soleAdminCannotBeDemoted() {
        // Demote admin1 first (should succeed since admin2 exists)
        membershipService.updateMemberRole(testOrg.getId(), admin1.getId(), Role.MANAGER);

        // Now admin2 is the sole remaining active admin
        assertThatThrownBy(() ->
                membershipService.updateMemberRole(testOrg.getId(), admin2.getId(), Role.OPERATOR)
        ).isInstanceOf(CannotDemoteLastAdminException.class)
         .hasMessageContaining("Cannot demote or remove the sole remaining administrator");

        // Verify admin2 is still active ADMIN in database
        Optional<OrganizationMembership> admin2Membership =
                membershipRepository.findByOrganizationIdAndUserId(testOrg.getId(), admin2.getId());
        assertThat(admin2Membership).isPresent();
        assertThat(admin2Membership.get().getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin2Membership.get().isActive()).isTrue();
    }

    @Test
    @DisplayName("Sole remaining admin cannot be deactivated (throws CannotDemoteLastAdminException)")
    void soleAdminCannotBeDeactivated() {
        // Demote admin1
        membershipService.updateMemberRole(testOrg.getId(), admin1.getId(), Role.VIEWER);

        // Attempt to deactivate admin2
        assertThatThrownBy(() ->
                membershipService.deactivateMember(testOrg.getId(), admin2.getId())
        ).isInstanceOf(CannotDemoteLastAdminException.class)
         .hasMessageContaining("Cannot demote or remove the sole remaining administrator");

        // Verify admin2 is still active ADMIN in database
        Optional<OrganizationMembership> admin2Membership =
                membershipRepository.findByOrganizationIdAndUserId(testOrg.getId(), admin2.getId());
        assertThat(admin2Membership).isPresent();
        assertThat(admin2Membership.get().isActive()).isTrue();
    }

    @Test
    @DisplayName("Concurrent demotion race condition is serialized by pessimistic lock, preventing zero-admin state")
    void concurrentDemotionPreventedByPessimisticLock() throws Exception {
        int threads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // Thread 1 attempts to demote admin1 to OPERATOR
        Future<?> f1 = executor.submit(() -> {
            OrganizationContextHolder.setContext(new OrganizationContext(
                    testOrg.getId(), admin1.getId(), Role.ADMIN, Set.of("membership:manage")
            ));
            readyLatch.countDown();
            try {
                startLatch.await();
                membershipService.updateMemberRole(testOrg.getId(), admin1.getId(), Role.OPERATOR);
                successCount.incrementAndGet();
            } catch (CannotDemoteLastAdminException ex) {
                failureCount.incrementAndGet();
            } catch (Exception ex) {
                // Ignore unexpected
            } finally {
                OrganizationContextHolder.clearContext();
            }
        });

        // Thread 2 attempts to demote admin2 to OPERATOR
        Future<?> f2 = executor.submit(() -> {
            OrganizationContextHolder.setContext(new OrganizationContext(
                    testOrg.getId(), admin2.getId(), Role.ADMIN, Set.of("membership:manage")
            ));
            readyLatch.countDown();
            try {
                startLatch.await();
                membershipService.updateMemberRole(testOrg.getId(), admin2.getId(), Role.OPERATOR);
                successCount.incrementAndGet();
            } catch (CannotDemoteLastAdminException ex) {
                failureCount.incrementAndGet();
            } catch (Exception ex) {
                // Ignore unexpected
            } finally {
                OrganizationContextHolder.clearContext();
            }
        });

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown(); // Release both threads simultaneously

        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        executor.shutdown();

        // Exactly one demotion must succeed, and exactly one must fail with CannotDemoteLastAdminException
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(1);

        // Assert at least 1 active ADMIN remains in database
        long remainingAdmins = membershipRepository.countByOrganizationIdAndRoleAndActiveTrue(testOrg.getId(), Role.ADMIN);
        assertThat(remainingAdmins).isEqualTo(1);
    }

    @Test
    @DisplayName("Dynamic database role change in PostgreSQL is immediately reflected by TenantMembershipValidator")
    void dynamicRoleChangeReflectedImmediately() {
        // Initially operator1 is OPERATOR
        Optional<TenantMembershipValidator.TenantMembershipDetails> initial =
                tenantMembershipValidator.validateMembership(operator1.getId(), testOrg.getId());
        assertThat(initial).isPresent();
        assertThat(initial.get().role()).isEqualTo(Role.OPERATOR);

        // Admin promotes operator1 to MANAGER in PostgreSQL
        membershipService.updateMemberRole(testOrg.getId(), operator1.getId(), Role.MANAGER);

        // Next validator call immediately sees MANAGER without any token re-issuance
        Optional<TenantMembershipValidator.TenantMembershipDetails> updated =
                tenantMembershipValidator.validateMembership(operator1.getId(), testOrg.getId());
        assertThat(updated).isPresent();
        assertThat(updated.get().role()).isEqualTo(Role.MANAGER);
    }
}
