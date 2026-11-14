package com.stockguard.order.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Order item entity mapped to PostgreSQL {@code order_items} table.
 * Preserves immutable historical product snapshots (SKU, name, unit price).
 */
@Entity
@Table(name = "order_items")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "sku_snapshot", nullable = false, length = 100, updatable = false)
    private String skuSnapshot;

    @Column(name = "name_snapshot", nullable = false, length = 255, updatable = false)
    private String nameSnapshot;

    @Column(name = "quantity", nullable = false)
    private long quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal unitPrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected OrderItem() {
        // JPA requirement
    }

    public OrderItem(
            UUID organizationId,
            UUID orderId,
            int lineNumber,
            UUID productId,
            String skuSnapshot,
            String nameSnapshot,
            long quantity,
            BigDecimal unitPrice
    ) {
        this.organizationId = Objects.requireNonNull(organizationId, "organizationId must not be null");
        this.orderId = Objects.requireNonNull(orderId, "orderId must not be null");
        this.lineNumber = lineNumber;
        this.productId = Objects.requireNonNull(productId, "productId must not be null");
        this.skuSnapshot = Objects.requireNonNull(skuSnapshot, "skuSnapshot must not be null");
        this.nameSnapshot = Objects.requireNonNull(nameSnapshot, "nameSnapshot must not be null");
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be strictly positive: " + quantity);
        }
        this.quantity = quantity;
        this.unitPrice = MonetaryValidator.validate(unitPrice, "unitPrice");
        this.createdAt = Instant.now();
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

    public int getLineNumber() {
        return lineNumber;
    }

    public UUID getProductId() {
        return productId;
    }

    public String getSkuSnapshot() {
        return skuSnapshot;
    }

    public String getNameSnapshot() {
        return nameSnapshot;
    }

    public long getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
