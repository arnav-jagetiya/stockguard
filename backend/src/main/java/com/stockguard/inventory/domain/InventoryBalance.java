package com.stockguard.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Inventory balance entity mapped to the PostgreSQL {@code inventory_balances} table.
 * <p>
 * Stores physical {@code on_hand} and committed {@code reserved}.
 * Available stock is strictly derived: {@code available = on_hand - reserved}.
 */
@Entity
@Table(name = "inventory_balances")
public class InventoryBalance {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "on_hand", nullable = false)
    private long onHand = 0L;

    @Column(name = "reserved", nullable = false)
    private long reserved = 0L;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected InventoryBalance() {
        // JPA requirement
    }

    public InventoryBalance(UUID organizationId, UUID productId, UUID warehouseId, long onHand, long reserved) {
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId must not be null");
        this.onHand = onHand;
        this.reserved = reserved;
        this.updatedAt = Instant.now();
        validateInvariants();
    }

    public void applyDelta(long onHandDelta, long reservedDelta) {
        this.onHand += onHandDelta;
        this.reserved += reservedDelta;
        this.updatedAt = Instant.now();
        validateInvariants();
    }

    public void validateInvariants() {
        if (this.onHand < 0) {
            throw new IllegalStateException("on_hand cannot be negative: " + this.onHand);
        }
        if (this.reserved < 0) {
            throw new IllegalStateException("reserved cannot be negative: " + this.reserved);
        }
        if (this.reserved > this.onHand) {
            throw new IllegalStateException(
                    "reserved (" + this.reserved + ") cannot exceed on_hand (" + this.onHand + ")"
            );
        }
    }

    public long getAvailable() {
        return this.onHand - this.reserved;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public long getOnHand() {
        return onHand;
    }

    public long getReserved() {
        return reserved;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
