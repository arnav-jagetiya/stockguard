package com.stockguard.reservation.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Reservation entity mapped to PostgreSQL {@code reservations} table.
 * Holds temporary inventory holds for PENDING customer orders.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private ReservationStatus status = ReservationStatus.ACTIVE;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Reservation() {
        // JPA requirement
    }

    public Reservation(
            UUID organizationId,
            UUID orderId,
            Instant expiresAt
    ) {
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        this.status = ReservationStatus.ACTIVE;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void updateStatus(ReservationStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "newStatus must not be null");
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public UUID getOrderId() {
        return orderId;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
