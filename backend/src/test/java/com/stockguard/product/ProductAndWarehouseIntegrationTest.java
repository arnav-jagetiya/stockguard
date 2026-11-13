package com.stockguard.product;

import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.product.domain.Product;
import com.stockguard.product.dto.CreateProductRequest;
import com.stockguard.product.dto.ProductResponse;
import com.stockguard.product.service.ProductService;
import com.stockguard.shared.exception.DuplicateResourceException;
import com.stockguard.warehouse.dto.CreateWarehouseRequest;
import com.stockguard.warehouse.dto.WarehouseResponse;
import com.stockguard.warehouse.service.WarehouseService;
import org.junit.jupiter.api.BeforeEach;
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
class ProductAndWarehouseIntegrationTest {

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
    private ProductService productService;

    @Autowired
    private WarehouseService warehouseService;

    private UUID orgAId;
    private UUID orgBId;

    @BeforeEach
    void setUp() {
        Organization orgA = organizationRepository.save(Organization.create("Organization A " + UUID.randomUUID()));
        Organization orgB = organizationRepository.save(Organization.create("Organization B " + UUID.randomUUID()));
        orgAId = orgA.getId();
        orgBId = orgB.getId();
    }

    @Test
    @DisplayName("Product SKU is unique within an organization but independent across organizations")
    void productSkuUniquePerOrgIndependentAcrossOrgs() {
        CreateProductRequest reqA = new CreateProductRequest("item-alpha", "Item Alpha Org A", "Org A item");
        ProductResponse prodA = productService.createProduct(orgAId, reqA);
        assertThat(prodA.normalizedSku()).isEqualTo("ITEM-ALPHA");

        // Attempt duplicate in Org A must fail
        CreateProductRequest dupReq = new CreateProductRequest("  ITEM-ALPHA  ", "Duplicate Item", null);
        assertThatThrownBy(() -> productService.createProduct(orgAId, dupReq))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already exists in organization");

        // Same SKU in Org B MUST succeed (cross-tenant independence)
        CreateProductRequest reqB = new CreateProductRequest("ITEM-ALPHA", "Item Alpha Org B", "Org B item");
        ProductResponse prodB = productService.createProduct(orgBId, reqB);
        assertThat(prodB.normalizedSku()).isEqualTo("ITEM-ALPHA");
        assertThat(prodB.organizationId()).isEqualTo(orgBId);
    }

    @Test
    @DisplayName("Warehouse code is unique within an organization but independent across organizations")
    void warehouseCodeUniquePerOrgIndependentAcrossOrgs() {
        CreateWarehouseRequest whA = new CreateWarehouseRequest("wh-east-01", "East WH Org A", "New York", 1);
        WarehouseResponse respA = warehouseService.createWarehouse(orgAId, whA);
        assertThat(respA.normalizedCode()).isEqualTo("WH-EAST-01");

        // Duplicate in Org A must fail
        CreateWarehouseRequest dupWh = new CreateWarehouseRequest("  WH-EAST-01 ", "Duplicate East WH", "Boston", 2);
        assertThatThrownBy(() -> warehouseService.createWarehouse(orgAId, dupWh))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already exists in organization");

        // Same code in Org B MUST succeed
        CreateWarehouseRequest whB = new CreateWarehouseRequest("WH-EAST-01", "East WH Org B", "Newark", 1);
        WarehouseResponse respB = warehouseService.createWarehouse(orgBId, whB);
        assertThat(respB.normalizedCode()).isEqualTo("WH-EAST-01");
        assertThat(respB.organizationId()).isEqualTo(orgBId);
    }

    @Test
    @DisplayName("Product search and soft deactivation in live PostgreSQL")
    void productSearchAndSoftDeactivation() {
        ProductResponse p1 = productService.createProduct(orgAId, new CreateProductRequest("LAPTOP-PRO", "Pro Laptop", null));
        ProductResponse p2 = productService.createProduct(orgAId, new CreateProductRequest("PHONE-MAX", "Max Phone", null));

        List<ProductResponse> allActive = productService.listProducts(orgAId, null, true);
        assertThat(allActive).extracting(ProductResponse::normalizedSku).contains("LAPTOP-PRO", "PHONE-MAX");

        // Soft deactivation
        productService.deactivateProduct(orgAId, p1.id());

        List<ProductResponse> activeAfterDeactivation = productService.listProducts(orgAId, null, true);
        assertThat(activeAfterDeactivation).extracting(ProductResponse::normalizedSku).doesNotContain("LAPTOP-PRO");
        assertThat(activeAfterDeactivation).extracting(ProductResponse::normalizedSku).contains("PHONE-MAX");

        List<ProductResponse> allIncludingInactive = productService.listProducts(orgAId, null, false);
        assertThat(allIncludingInactive).extracting(ProductResponse::normalizedSku).contains("LAPTOP-PRO", "PHONE-MAX");
    }
}
