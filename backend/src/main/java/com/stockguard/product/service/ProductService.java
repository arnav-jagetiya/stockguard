package com.stockguard.product.service;

import com.stockguard.product.domain.Product;
import com.stockguard.product.dto.CreateProductRequest;
import com.stockguard.product.dto.ProductResponse;
import com.stockguard.product.dto.UpdateProductRequest;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.shared.exception.DuplicateResourceException;
import com.stockguard.shared.exception.ResourceNotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Profile("!test")
@Transactional
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public ProductResponse createProduct(UUID organizationId, CreateProductRequest request) {
        String normalizedSku = Product.normalizeSku(request.sku());
        if (productRepository.existsByOrganizationIdAndNormalizedSku(organizationId, normalizedSku)) {
            throw new DuplicateResourceException(
                    "Product with SKU '" + normalizedSku + "' already exists in organization " + organizationId
            );
        }

        Product product = new Product(
                organizationId,
                request.sku(),
                request.name(),
                request.description()
        );

        Product saved = productRepository.save(product);
        return ProductResponse.fromDomain(saved);
    }

    @Transactional(readOnly = true)
    public ProductResponse getProduct(UUID organizationId, UUID productId) {
        Product product = productRepository.findByIdAndOrganizationId(productId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));
        return ProductResponse.fromDomain(product);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> listProducts(UUID organizationId, String query, boolean activeOnly) {
        List<Product> products = productRepository.searchProducts(organizationId, query, activeOnly);
        return products.stream()
                .map(ProductResponse::fromDomain)
                .toList();
    }

    public ProductResponse updateProduct(UUID organizationId, UUID productId, UpdateProductRequest request) {
        Product product = productRepository.findByIdAndOrganizationId(productId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        product.updateDetails(request.name(), request.description());
        Product saved = productRepository.save(product);
        return ProductResponse.fromDomain(saved);
    }

    public ProductResponse deactivateProduct(UUID organizationId, UUID productId) {
        Product product = productRepository.findByIdAndOrganizationId(productId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        product.deactivate();
        Product saved = productRepository.save(product);
        return ProductResponse.fromDomain(saved);
    }
}
