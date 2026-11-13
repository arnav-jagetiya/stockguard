package com.stockguard.product.service;

import com.stockguard.product.domain.Product;
import com.stockguard.product.dto.CreateProductRequest;
import com.stockguard.product.dto.ProductResponse;
import com.stockguard.product.dto.UpdateProductRequest;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.shared.exception.DuplicateResourceException;
import com.stockguard.shared.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProductServiceTest {

    private ProductRepository productRepository;
    private ProductService productService;
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        productService = new ProductService(productRepository);
    }

    @Test
    @DisplayName("Create product normalizes SKU to uppercase trimmed string and persists product")
    void createProductNormalizesSku() {
        CreateProductRequest request = new CreateProductRequest("  sku-widget-100  ", "Widget 100", "A test widget");
        when(productRepository.existsByOrganizationIdAndNormalizedSku(eq(orgId), eq("SKU-WIDGET-100"))).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponse response = productService.createProduct(orgId, request);

        assertThat(response.sku()).isEqualTo("sku-widget-100");
        assertThat(response.normalizedSku()).isEqualTo("SKU-WIDGET-100");
        assertThat(response.name()).isEqualTo("Widget 100");
        assertThat(response.description()).isEqualTo("A test widget");
        assertThat(response.active()).isTrue();

        ArgumentCaptor<Product> captor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(captor.capture());
        assertThat(captor.getValue().getNormalizedSku()).isEqualTo("SKU-WIDGET-100");
    }

    @Test
    @DisplayName("Create product throws DuplicateResourceException when normalized SKU already exists in organization")
    void createProductDuplicateSkuThrowsException() {
        CreateProductRequest request = new CreateProductRequest("SKU-EXISTING", "Existing Widget", null);
        when(productRepository.existsByOrganizationIdAndNormalizedSku(eq(orgId), eq("SKU-EXISTING"))).thenReturn(true);

        assertThatThrownBy(() -> productService.createProduct(orgId, request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("SKU 'SKU-EXISTING' already exists");

        verify(productRepository, never()).save(any());
    }

    @Test
    @DisplayName("Get product by ID returns product when found in tenant")
    void getProductFound() {
        UUID productId = UUID.randomUUID();
        Product product = new Product(orgId, "SKU-1", "Product 1", "Desc");
        when(productRepository.findByIdAndOrganizationId(productId, orgId)).thenReturn(Optional.of(product));

        ProductResponse response = productService.getProduct(orgId, productId);
        assertThat(response.name()).isEqualTo("Product 1");
    }

    @Test
    @DisplayName("Get product throws ResourceNotFoundException when product does not exist in tenant")
    void getProductNotFoundThrows() {
        UUID productId = UUID.randomUUID();
        when(productRepository.findByIdAndOrganizationId(productId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> productService.getProduct(orgId, productId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Product not found");
    }

    @Test
    @DisplayName("Update product modifies name and description while preserving SKU and identity")
    void updateProductModifiesDetails() {
        UUID productId = UUID.randomUUID();
        Product product = new Product(orgId, "SKU-IMMUTABLE", "Original Name", "Original Desc");
        when(productRepository.findByIdAndOrganizationId(productId, orgId)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateProductRequest updateReq = new UpdateProductRequest("Updated Name", "Updated Desc");
        ProductResponse response = productService.updateProduct(orgId, productId, updateReq);

        assertThat(response.name()).isEqualTo("Updated Name");
        assertThat(response.description()).isEqualTo("Updated Desc");
        assertThat(response.normalizedSku()).isEqualTo("SKU-IMMUTABLE");
    }

    @Test
    @DisplayName("Deactivate product sets active flag to false without deleting row")
    void deactivateProductSetsActiveFalse() {
        UUID productId = UUID.randomUUID();
        Product product = new Product(orgId, "SKU-DEACT", "Deactivate Me", null);
        assertThat(product.isActive()).isTrue();

        when(productRepository.findByIdAndOrganizationId(productId, orgId)).thenReturn(Optional.of(product));
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponse response = productService.deactivateProduct(orgId, productId);

        assertThat(response.active()).isFalse();
        verify(productRepository, never()).delete(any());
    }
}
