package com.stockguard.product.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Product domain entity mapped to the PostgreSQL {@code products} table.
 */
@Entity
@Table(name = "products")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "sku", nullable = false, length = 100, updatable = false)
    private String sku;

    @Column(name = "normalized_sku", nullable = false, length = 100, updatable = false)
    private String normalizedSku;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Product() {
        // JPA requirement
    }

    public Product(UUID organizationId, String sku, String name, String description) {
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.sku = validateSku(sku);
        this.normalizedSku = normalizeSku(sku);
        this.name = validateName(name);
        this.description = description;
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public static String normalizeSku(String rawSku) {
        if (rawSku == null || rawSku.trim().isEmpty()) {
            throw new IllegalArgumentException("SKU must not be blank");
        }
        return rawSku.trim().toUpperCase(Locale.ROOT);
    }

    private static String validateSku(String rawSku) {
        if (rawSku == null || rawSku.trim().isEmpty()) {
            throw new IllegalArgumentException("SKU must not be blank");
        }
        String trimmed = rawSku.trim();
        if (trimmed.length() > 100) {
            throw new IllegalArgumentException("SKU must not exceed 100 characters");
        }
        return trimmed;
    }

    private static String validateName(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            throw new IllegalArgumentException("Product name must not be blank");
        }
        String trimmed = rawName.trim();
        if (trimmed.length() > 255) {
            throw new IllegalArgumentException("Product name must not exceed 255 characters");
        }
        return trimmed;
    }

    public void updateDetails(String newName, String newDescription) {
        this.name = validateName(newName);
        this.description = newDescription;
        this.updatedAt = Instant.now();
    }

    public void deactivate() {
        this.active = false;
        this.updatedAt = Instant.now();
    }

    public void activate() {
        this.active = true;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getSku() {
        return sku;
    }

    public String getNormalizedSku() {
        return normalizedSku;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
