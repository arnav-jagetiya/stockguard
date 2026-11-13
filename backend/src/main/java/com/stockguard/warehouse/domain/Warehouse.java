package com.stockguard.warehouse.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Warehouse domain entity mapped to the PostgreSQL {@code warehouses} table.
 */
@Entity
@Table(name = "warehouses")
public class Warehouse {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "code", nullable = false, length = 50, updatable = false)
    private String code;

    @Column(name = "normalized_code", nullable = false, length = 50, updatable = false)
    private String normalizedCode;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "location", length = 255)
    private String location;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Warehouse() {
        // JPA requirement
    }

    public Warehouse(UUID organizationId, String code, String name, String location, int priority) {
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.code = validateCode(code);
        this.normalizedCode = normalizeCode(code);
        this.name = validateName(name);
        this.location = location;
        this.priority = priority;
        this.active = true;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public static String normalizeCode(String rawCode) {
        if (rawCode == null || rawCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Warehouse code must not be blank");
        }
        return rawCode.trim().toUpperCase(Locale.ROOT);
    }

    private static String validateCode(String rawCode) {
        if (rawCode == null || rawCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Warehouse code must not be blank");
        }
        String trimmed = rawCode.trim();
        if (trimmed.length() > 50) {
            throw new IllegalArgumentException("Warehouse code must not exceed 50 characters");
        }
        return trimmed;
    }

    private static String validateName(String rawName) {
        if (rawName == null || rawName.trim().isEmpty()) {
            throw new IllegalArgumentException("Warehouse name must not be blank");
        }
        String trimmed = rawName.trim();
        if (trimmed.length() > 255) {
            throw new IllegalArgumentException("Warehouse name must not exceed 255 characters");
        }
        return trimmed;
    }

    public void updateDetails(String newName, String newLocation, int newPriority) {
        this.name = validateName(newName);
        this.location = newLocation;
        this.priority = newPriority;
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

    public String getCode() {
        return code;
    }

    public String getNormalizedCode() {
        return normalizedCode;
    }

    public String getName() {
        return name;
    }

    public String getLocation() {
        return location;
    }

    public int getPriority() {
        return priority;
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
