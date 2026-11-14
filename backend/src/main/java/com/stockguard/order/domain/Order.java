package com.stockguard.order.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Order domain entity mapped to PostgreSQL {@code orders} table.
 */
@Entity
@Table(name = "orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "order_number", nullable = false, length = 100, updatable = false)
    private String orderNumber;

    @Column(name = "customer_reference", length = 255)
    private String customerReference;

    @Column(name = "external_order_reference", length = 255)
    private String externalOrderReference;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private OrderStatus status = OrderStatus.PENDING;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency = "USD";

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected Order() {
        // JPA requirement
    }

    public Order(
            UUID organizationId,
            String orderNumber,
            String customerReference,
            String externalOrderReference,
            String currency,
            BigDecimal totalAmount,
            UUID actorUserId
    ) {
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.orderNumber = Objects.requireNonNull(orderNumber, "orderNumber must not be null");
        this.customerReference = customerReference;
        this.externalOrderReference = externalOrderReference;
        this.status = OrderStatus.PENDING;
        this.currency = (currency != null && !currency.isBlank()) ? currency.trim().toUpperCase() : "USD";
        this.totalAmount = MonetaryValidator.validate(totalAmount, "totalAmount");
        this.actorUserId = actorUserId;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public void updateStatus(OrderStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "newStatus must not be null");
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getOrganizationId() {
        return organizationId;
    }

    public String getOrderNumber() {
        return orderNumber;
    }

    public String getCustomerReference() {
        return customerReference;
    }

    public String getExternalOrderReference() {
        return externalOrderReference;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public UUID getActorUserId() {
        return actorUserId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
