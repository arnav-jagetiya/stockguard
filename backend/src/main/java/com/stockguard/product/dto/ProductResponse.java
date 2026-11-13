package com.stockguard.product.dto;

import com.stockguard.product.domain.Product;

import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        UUID organizationId,
        String sku,
        String normalizedSku,
        String name,
        String description,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static ProductResponse fromDomain(Product product) {
        return new ProductResponse(
                product.getId(),
                product.getOrganizationId(),
                product.getSku(),
                product.getNormalizedSku(),
                product.getName(),
                product.getDescription(),
                product.isActive(),
                product.getCreatedAt(),
                product.getUpdatedAt()
        );
    }
}
