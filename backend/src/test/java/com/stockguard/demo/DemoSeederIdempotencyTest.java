package com.stockguard.demo;

import com.stockguard.demo.config.DemoProperties;
import com.stockguard.demo.dto.DemoSeedResult;
import com.stockguard.demo.service.DemoEnvironmentSeeder;
import com.stockguard.inventory.repository.InventoryBalanceRepository;
import com.stockguard.order.repository.OrderRepository;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.reservation.repository.ReservationRepository;
import com.stockguard.security.bootstrap.AdminBootstrapRunner;
import com.stockguard.warehouse.repository.WarehouseRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class DemoSeederIdempotencyTest {

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
        registry.add("stockguard.demo.seed-enabled", () -> "false");
    }

    @Autowired
    private DemoEnvironmentSeeder demoEnvironmentSeeder;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryBalanceRepository balanceRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ReservationRepository reservationRepository;

    @Test
    @DisplayName("Idempotent repeated seed runs preserve exact database state and pre-existing organizations")
    void repeatedRunsAreIdempotentAndPreserveData() {
        // Pre-create another unrelated organization to verify strict preservation
        Organization existingOrg = Organization.create("Pre-Existing Legacy Enterprise");
        existingOrg = organizationRepository.saveAndFlush(existingOrg);
        UUID legacyOrgId = existingOrg.getId();

        DemoProperties properties = new DemoProperties();
        properties.setSeedEnabled(true);
        properties.setOrgName("Acme Industrial Supply Pvt. Ltd.");
        properties.setAdminEmail("acme-admin-idemp@stockguard.dev");
        properties.setAdminPassword(AdminBootstrapRunner.generateSecure256BitHexCredential());
        properties.setAdminFullName("Aarav Mehta");

        // Run 1: Initial seed
        DemoSeedResult result1 = demoEnvironmentSeeder.seedDemoEnvironment(properties);
        assertThat(result1.alreadySeeded()).isFalse();
        UUID acmeOrgId = result1.organizationId();

        // Capture snapshot counts after run 1
        long orgCountBefore = organizationRepository.count();
        long memberCountBefore = membershipRepository.count();
        long whCountBefore = warehouseRepository.count();
        long prodCountBefore = productRepository.count();
        long balCountBefore = balanceRepository.count();
        long orderCountBefore = orderRepository.count();
        long resCountBefore = reservationRepository.count();

        // Run 2: Repeated seed
        DemoSeedResult result2 = demoEnvironmentSeeder.seedDemoEnvironment(properties);
        assertThat(result2.alreadySeeded()).isTrue();
        assertThat(result2.organizationId()).isEqualTo(acmeOrgId);

        // Verify record counts remain strictly identical
        assertThat(organizationRepository.count()).isEqualTo(orgCountBefore);
        assertThat(membershipRepository.count()).isEqualTo(memberCountBefore);
        assertThat(warehouseRepository.count()).isEqualTo(whCountBefore);
        assertThat(productRepository.count()).isEqualTo(prodCountBefore);
        assertThat(balanceRepository.count()).isEqualTo(balCountBefore);
        assertThat(orderRepository.count()).isEqualTo(orderCountBefore);
        assertThat(reservationRepository.count()).isEqualTo(resCountBefore);

        // Verify pre-existing organization was not altered or deleted
        assertThat(organizationRepository.findById(legacyOrgId)).isPresent();
    }
}
