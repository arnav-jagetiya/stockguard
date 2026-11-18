package com.stockguard.demo;

import com.stockguard.demo.config.DemoProperties;
import com.stockguard.demo.dto.DemoSeedResult;
import com.stockguard.demo.service.DemoEnvironmentSeeder;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.product.domain.Product;
import com.stockguard.product.dto.CreateProductRequest;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.product.service.ProductService;
import com.stockguard.security.bootstrap.AdminBootstrapRunner;
import com.stockguard.security.context.OrganizationContext;
import com.stockguard.security.context.OrganizationContextHolder;
import com.stockguard.security.domain.User;
import com.stockguard.security.repository.UserRepository;
import com.stockguard.shared.exception.ResourceNotFoundException;
import com.stockguard.warehouse.domain.Warehouse;
import com.stockguard.warehouse.dto.CreateWarehouseRequest;
import com.stockguard.warehouse.repository.WarehouseRepository;
import com.stockguard.warehouse.service.WarehouseService;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class DemoOrganizationIsolationTest {

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
    private UserRepository userRepository;

    @Autowired
    private OrganizationMembershipRepository membershipRepository;

    @Autowired
    private ProductService productService;

    @Autowired
    private WarehouseService warehouseService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Test
    @DisplayName("Tenant isolation strictly enforces data partitioning across organizations")
    void organizationIsolationEnforced() {
        // 1. Seed Acme Industrial Supply
        DemoProperties properties = new DemoProperties();
        properties.setSeedEnabled(true);
        properties.setOrgName("Acme Industrial Supply Pvt. Ltd.");
        properties.setAdminEmail("acme-admin-iso@stockguard.dev");
        properties.setAdminPassword(AdminBootstrapRunner.generateSecure256BitHexCredential());
        properties.setAdminFullName("Aarav Mehta");

        DemoSeedResult result = demoEnvironmentSeeder.seedDemoEnvironment(properties);
        UUID acmeOrgId = result.organizationId();

        // 2. Create another independent organization
        Organization otherOrg = Organization.create("Independent Logistics Corp");
        otherOrg = organizationRepository.saveAndFlush(otherOrg);
        UUID otherOrgId = otherOrg.getId();

        // Create warehouse in other organization
        warehouseService.createWarehouse(otherOrgId, new CreateWarehouseRequest("WH-OTH-01", "Other Facility", "Chennai", 1));

        // 3. Verify Acme query does NOT return Other Org's warehouse
        List<Warehouse> acmeWarehouses = warehouseRepository.searchWarehouses(acmeOrgId, null, false);
        assertThat(acmeWarehouses).extracting(Warehouse::getCode).doesNotContain("WH-OTH-01");

        // 4. Verify Other Org query does NOT return Acme's warehouses
        List<Warehouse> otherWarehouses = warehouseRepository.searchWarehouses(otherOrgId, null, false);
        assertThat(otherWarehouses).hasSize(1);
        assertThat(otherWarehouses.get(0).getCode()).isEqualTo("WH-OTH-01");

        // 5. Verify cross-tenant resource retrieval throws ResourceNotFoundException
        Warehouse acmeWh = acmeWarehouses.get(0);
        assertThatThrownBy(() -> warehouseService.getWarehouse(otherOrgId, acmeWh.getId()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
