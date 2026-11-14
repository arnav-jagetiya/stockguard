package com.stockguard.reservation.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Reservation allocation entity mapped to PostgreSQL {@code reservation_allocations} table.
 * Preserves historical mapping between an order line item and the warehouse fulfilling it.
 */
@Entity
@Table(name = "reservation_allocations")
public class ReservationAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "reservation_id", nullable = false, updatable = false)
    private UUID reservationId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "order_item_id", nullable = false, updatable = false)
    private UUID orderItemId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Column(name = "quantity", nullable = false, updatable = false)
    private long quantity;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected ReservationAllocation() {
        // JPA requirement
    }

    public ReservationAllocation(
            UUID organizationId,
            UUID reservationId,
            UUID orderId,
            UUID orderItemId,
            UUID productId,
            UUID warehouseId,
            long quantity
    ) {
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.reservationId = Objects.requireNonNull(reservationId, "reservationId must not be null");
        this.orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        this.orderItemId = Objects.requireNonNull(orderItemId, "orderItemId must not be null");
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
        this.warehouseId = Objects.requireNonNull(warehouseId, "warehouseId must not be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be strictly positive: " + quantity);
        }
        this.quantity = quantity;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public UUID getOrderItemId() {
        return orderItemId;
    }

    public UUID getProductId() {
        return productId;
    }

    public UUID getWarehouseId() {
        return warehouseId;
    }

    public long getQuantity() {
        return quantity;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
