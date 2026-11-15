package com.stockguard.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA Entity mapping the {@code reconciliation_discrepancies} table per ADR-012.
 */
@Entity
@Table(name = "reconciliation_discrepancies")
public class ReconciliationDiscrepancyEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(name = "warehouse_id", nullable = false, updatable = false)
    private UUID warehouseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "discrepancy_type", nullable = false, length = 30)
    private DiscrepancyType discrepancyType;

    @Column(name = "observed_balance_on_hand", nullable = false)
    private long observedBalanceOnHand;

    @Column(name = "observed_ledger_on_hand", nullable = false)
    private long observedLedgerOnHand;

    @Column(name = "observed_balance_reserved", nullable = false)
    private long observedBalanceReserved;

    @Column(name = "observed_ledger_reserved", nullable = false)
    private long observedLedgerReserved;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private DiscrepancyStatus status;

    @Column(name = "consecutive_detection_count", nullable = false)
    private int consecutiveDetectionCount;

    @Column(name = "first_detected_at", nullable = false, updatable = false)
    private Instant firstDetectedAt;

    @Column(name = "last_detected_at", nullable = false)
    private Instant lastDetectedAt;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "assigned_to_user_id")
    private UUID assignedToUserId;

    @Column(name = "investigation_notes")
    private String investigationNotes;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_source", length = 20)
    private ResolutionSource resolutionSource;

    @Column(name = "resolution_reason")
    private String resolutionReason;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_by_user_id")
    private UUID resolvedByUserId;

    @Version
    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReconciliationDiscrepancyEntity() {
        // JPA requirement
    }

    public ReconciliationDiscrepancyEntity(
            UUID id,
            UUID organizationId,
            UUID productId,
            UUID warehouseId,
            DiscrepancyType discrepancyType,
            long observedBalanceOnHand,
            long observedLedgerOnHand,
            long observedBalanceReserved,
            long observedLedgerReserved
    ) {
        this.id = Objects.requireNonNull(id, "ID must not be null");
        this.organizationId = Objects.requireNonNull(organizationId, "Organization ID must not be null");
        this.productId = Objects.requireNonNull(productId, "Product ID must not be null");
        this.warehouseId = Objects.requireNonNull(warehouseId, "Warehouse ID must not be null");
        this.discrepancyType = Objects.requireNonNull(discrepancyType, "Discrepancy type must not be null");
        this.observedBalanceOnHand = observedBalanceOnHand;
        this.observedLedgerOnHand = observedLedgerOnHand;
        this.observedBalanceReserved = observedBalanceReserved;
        this.observedLedgerReserved = observedLedgerReserved;

        this.status = DiscrepancyStatus.OPEN;
        this.consecutiveDetectionCount = 1;

        Instant now = Instant.now();
        this.firstDetectedAt = now;
        this.lastDetectedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
        this.version = 0;
    }

    /**
     * Updates an existing active discrepancy on repeated observation of a mismatch.
     */
    public void recordRepeatedDetection(
            DiscrepancyType newType,
            long newBalanceOnHand,
            long newLedgerOnHand,
            long newBalanceReserved,
            long newLedgerReserved
    ) {
        this.discrepancyType = Objects.requireNonNull(newType, "New discrepancy type must not be null");
        this.observedBalanceOnHand = newBalanceOnHand;
        this.observedLedgerOnHand = newLedgerOnHand;
        this.observedBalanceReserved = newBalanceReserved;
        this.observedLedgerReserved = newLedgerReserved;
        this.consecutiveDetectionCount++;
        this.lastDetectedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * Auto-resolves an OPEN discrepancy when clean parity is observed by the background worker.
     */
    public void autoResolveBySystem(String reason) {
        if (this.status != DiscrepancyStatus.OPEN) {
            throw new IllegalStateException("Only OPEN discrepancies can be auto-resolved by the system, current status: " + this.status);
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Resolution reason must not be blank");
        }
        Instant now = Instant.now();
        this.status = DiscrepancyStatus.RESOLVED;
        this.resolutionSource = ResolutionSource.SYSTEM;
        this.resolvedByUserId = null;
        this.resolvedAt = now;
        this.resolutionReason = reason;
        this.lastVerifiedAt = now;
        this.updatedAt = now;
    }

    /**
     * Records observation of clean parity for an actively investigated discrepancy without closing it.
     */
    public void updateParityVerified() {
        this.lastVerifiedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    // Getters and Setters

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

    public DiscrepancyType getDiscrepancyType() {
        return discrepancyType;
    }

    public void setDiscrepancyType(DiscrepancyType discrepancyType) {
        this.discrepancyType = discrepancyType;
    }

    public long getObservedBalanceOnHand() {
        return observedBalanceOnHand;
    }

    public long getObservedLedgerOnHand() {
        return observedLedgerOnHand;
    }

    public long getObservedBalanceReserved() {
        return observedBalanceReserved;
    }

    public long getObservedLedgerReserved() {
        return observedLedgerReserved;
    }

    public DiscrepancyStatus getStatus() {
        return status;
    }

    public void setStatus(DiscrepancyStatus status) {
        this.status = status;
    }

    public int getConsecutiveDetectionCount() {
        return consecutiveDetectionCount;
    }

    public void setConsecutiveDetectionCount(int consecutiveDetectionCount) {
        this.consecutiveDetectionCount = consecutiveDetectionCount;
    }

    public Instant getFirstDetectedAt() {
        return firstDetectedAt;
    }

    public Instant getLastDetectedAt() {
        return lastDetectedAt;
    }

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public void setLastVerifiedAt(Instant lastVerifiedAt) {
        this.lastVerifiedAt = lastVerifiedAt;
    }

    public UUID getAssignedToUserId() {
        return assignedToUserId;
    }

    public void setAssignedToUserId(UUID assignedToUserId) {
        this.assignedToUserId = assignedToUserId;
    }

    public String getInvestigationNotes() {
        return investigationNotes;
    }

    public void setInvestigationNotes(String investigationNotes) {
        this.investigationNotes = investigationNotes;
    }

    public ResolutionSource getResolutionSource() {
        return resolutionSource;
    }

    public void setResolutionSource(ResolutionSource resolutionSource) {
        this.resolutionSource = resolutionSource;
    }

    public String getResolutionReason() {
        return resolutionReason;
    }

    public void setResolutionReason(String resolutionReason) {
        this.resolutionReason = resolutionReason;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public UUID getResolvedByUserId() {
        return resolvedByUserId;
    }

    public void setResolvedByUserId(UUID resolvedByUserId) {
        this.resolvedByUserId = resolvedByUserId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
