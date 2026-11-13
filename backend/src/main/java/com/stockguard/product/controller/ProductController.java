package com.stockguard.product.controller;

import com.stockguard.product.dto.CreateProductRequest;
import com.stockguard.product.dto.ProductResponse;
import com.stockguard.product.dto.UpdateProductRequest;
import com.stockguard.product.service.ProductService;
import com.stockguard.security.context.OrganizationContext;
import com.stockguard.security.context.OrganizationContextHolder;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/products")
@Profile("!test")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('product:manage')")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody CreateProductRequest request) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        ProductResponse response = productService.createProduct(context.organizationId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('product:view')")
    public ResponseEntity<List<ProductResponse>> listProducts(
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "activeOnly", defaultValue = "false") boolean activeOnly
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        List<ProductResponse> response = productService.listProducts(context.organizationId(), search, activeOnly);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{productId}")
    @PreAuthorize("hasAuthority('product:view')")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable UUID productId) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        ProductResponse response = productService.getProduct(context.organizationId(), productId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{productId}")
    @PreAuthorize("hasAuthority('product:manage')")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable UUID productId,
            @Valid @RequestBody UpdateProductRequest request
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        ProductResponse response = productService.updateProduct(context.organizationId(), productId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{productId}")
    @PreAuthorize("hasAuthority('product:manage')")
    public ResponseEntity<ProductResponse> deactivateProduct(@PathVariable UUID productId) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        ProductResponse response = productService.deactivateProduct(context.organizationId(), productId);
        return ResponseEntity.ok(response);
    }
}
