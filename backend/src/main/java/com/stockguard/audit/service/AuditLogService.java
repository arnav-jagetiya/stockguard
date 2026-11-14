package com.stockguard.audit.service;

import com.stockguard.audit.domain.AuditAction;
import com.stockguard.audit.domain.AuditEntityType;
import com.stockguard.audit.domain.AuditLog;
import com.stockguard.audit.repository.JdbcAuditLogOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Service managing transactional business audit records and independent security audit records.
 * <p>
 * Enforces explicit domain-specific method parameters and allowlisted payloads.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);
    private static final int MAX_DISCREPANCY_ITEMS = 100;

    private final JdbcAuditLogOperations jdbcAuditLogOperations;
    private final AuditLogTransactionDelegate transactionDelegate;

    public AuditLogService(
            JdbcAuditLogOperations jdbcAuditLogOperations,
            AuditLogTransactionDelegate transactionDelegate
    ) {
        this.jdbcAuditLogOperations = jdbcAuditLogOperations;
        this.transactionDelegate = transactionDelegate;
    }

    // =========================================================================
    // 1. INVENTORY DOMAIN AUDIT METHODS (Propagation.REQUIRED)
    // =========================================================================

    @Transactional(propagation = Propagation.REQUIRED)
    public void logInventoryAdjustment(
            UUID organizationId,
            UUID actorUserId,
            UUID productId,
            UUID warehouseId,
            long beforeOnHand,
            long beforeReserved,
            long onHandDelta,
            String reason,
            UUID transactionId
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for user-initiated stock adjustment");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(warehouseId, "warehouseId must not be null");

        Map<String, Object> beforeState = new LinkedHashMap<>();
        beforeState.put("on_hand", beforeOnHand);
        beforeState.put("reserved", beforeReserved);

        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("on_hand", beforeOnHand + onHandDelta);
        afterState.put("reserved", beforeReserved);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("productId", productId.toString());
        metadata.put("warehouseId", warehouseId.toString());
        metadata.put("onHandDelta", onHandDelta);
        metadata.put("reason", reason);
        if (transactionId != null) {
            metadata.put("transactionId", transactionId.toString());
        }

        jdbcAuditLogOperations.insertAuditLog(
                UUID.randomUUID(),
                organizationId,
                actorUserId,
                AuditAction.STOCK_ADJUSTED,
                AuditEntityType.INVENTORY_BALANCE,
                productId,
                beforeState,
                afterState,
                metadata
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logInventoryAdjustment(
            UUID organizationId,
            UUID actorUserId,
            UUID productId,
            UUID warehouseId,
            long onHandDelta,
            String reason,
            UUID transactionId
    ) {
        logInventoryAdjustment(organizationId, actorUserId, productId, warehouseId, 0L, 0L, onHandDelta, reason, transactionId);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logOpeningBalance(
            UUID organizationId,
            UUID actorUserId,
            UUID productId,
            UUID warehouseId,
            long beforeOnHand,
            long beforeReserved,
            long onHand,
            String notes,
            UUID transactionId
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for user-initiated opening balance");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(warehouseId, "warehouseId must not be null");

        Map<String, Object> beforeState = new LinkedHashMap<>();
        beforeState.put("on_hand", beforeOnHand);
        beforeState.put("reserved", beforeReserved);

        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("on_hand", onHand);
        afterState.put("reserved", 0L);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("productId", productId.toString());
        metadata.put("warehouseId", warehouseId.toString());
        metadata.put("onHand", onHand);
        if (notes != null && !notes.isBlank()) {
            metadata.put("notes", notes);
        }
        if (transactionId != null) {
            metadata.put("transactionId", transactionId.toString());
        }

        jdbcAuditLogOperations.insertAuditLog(
                UUID.randomUUID(),
                organizationId,
                actorUserId,
                AuditAction.OPENING_BALANCE_RECORDED,
                AuditEntityType.INVENTORY_BALANCE,
                productId,
                beforeState,
                afterState,
                metadata
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logOpeningBalance(
            UUID organizationId,
            UUID actorUserId,
            UUID productId,
            UUID warehouseId,
            long onHand,
            UUID transactionId
    ) {
        logOpeningBalance(organizationId, actorUserId, productId, warehouseId, 0L, 0L, onHand, null, transactionId);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logStockReceipt(
            UUID organizationId,
            UUID actorUserId,
            UUID productId,
            UUID warehouseId,
            long beforeOnHand,
            long beforeReserved,
            long quantityReceived,
            String referenceId,
            String notes,
            UUID transactionId
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for user-initiated stock receipt");
        Objects.requireNonNull(productId, "productId must not be null");
        Objects.requireNonNull(warehouseId, "warehouseId must not be null");

        Map<String, Object> beforeState = new LinkedHashMap<>();
        beforeState.put("on_hand", beforeOnHand);
        beforeState.put("reserved", beforeReserved);

        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("on_hand", beforeOnHand + quantityReceived);
        afterState.put("reserved", beforeReserved);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("productId", productId.toString());
        metadata.put("warehouseId", warehouseId.toString());
        metadata.put("quantityReceived", quantityReceived);
        if (referenceId != null && !referenceId.isBlank()) {
            metadata.put("referenceId", referenceId);
        }
        if (notes != null && !notes.isBlank()) {
            metadata.put("notes", notes);
        }
        if (transactionId != null) {
            metadata.put("transactionId", transactionId.toString());
        }

        jdbcAuditLogOperations.insertAuditLog(
                UUID.randomUUID(),
                organizationId,
                actorUserId,
                AuditAction.STOCK_RECEIVED,
                AuditEntityType.INVENTORY_BALANCE,
                productId,
                beforeState,
                afterState,
                metadata
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logStockReceipt(
            UUID organizationId,
            UUID actorUserId,
            UUID productId,
            UUID warehouseId,
            long quantityReceived,
            UUID transactionId
    ) {
        logStockReceipt(organizationId, actorUserId, productId, warehouseId, 0L, 0L, quantityReceived, null, null, transactionId);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logTransferExecuted(
            UUID organizationId,
            UUID actorUserId,
            UUID transferId,
            UUID sourceWarehouseId,
            UUID destWarehouseId,
            int itemCount,
            UUID transactionId,
            List<Map<String, Object>> items
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for user-initiated stock transfer");
        Objects.requireNonNull(transferId, "transferId must not be null");

        Map<String, Object> beforeState = new LinkedHashMap<>();
        beforeState.put("sourceWarehouseId", sourceWarehouseId.toString());
        beforeState.put("destWarehouseId", destWarehouseId.toString());

        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("status", "COMPLETED");
        afterState.put("itemCount", itemCount);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sourceWarehouseId", sourceWarehouseId.toString());
        metadata.put("destWarehouseId", destWarehouseId.toString());
        metadata.put("itemCount", itemCount);
        if (transactionId != null) {
            metadata.put("transactionId", transactionId.toString());
        }
        if (items != null && !items.isEmpty()) {
            metadata.put("items", items);
        }

        jdbcAuditLogOperations.insertAuditLog(
                UUID.randomUUID(),
                organizationId,
                actorUserId,
                AuditAction.TRANSFER_COMPLETED,
                AuditEntityType.TRANSFER,
                transferId,
                beforeState,
                afterState,
                metadata
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logTransferExecuted(
            UUID organizationId,
            UUID actorUserId,
            UUID transferId,
            UUID sourceWarehouseId,
            UUID destWarehouseId,
            int itemCount,
            UUID transactionId
    ) {
        logTransferExecuted(organizationId, actorUserId, transferId, sourceWarehouseId, destWarehouseId, itemCount, transactionId, null);
    }

    // =========================================================================
    // 2. ORDER & RESERVATION AUDIT METHODS (Propagation.REQUIRED)
    // =========================================================================

    @Transactional(propagation = Propagation.REQUIRED)
    public void logOrderCreated(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId,
            String orderNumber,
            String currency,
            BigDecimal totalAmount,
            int lineItemCount,
            String customerReference,
            String externalOrderReference
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for user-initiated order creation");
        Objects.requireNonNull(orderId, "orderId must not be null");

        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("status", "PENDING");
        afterState.put("currency", currency != null ? currency : "USD");
        afterState.put("totalAmount", totalAmount != null ? totalAmount.toPlainString() : "0.00");
        afterState.put("lineItemCount", lineItemCount);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderNumber", orderNumber);
        if (customerReference != null && !customerReference.isBlank()) {
            metadata.put("customerReference", customerReference);
        }
        if (externalOrderReference != null && !externalOrderReference.isBlank()) {
            metadata.put("externalOrderReference", externalOrderReference);
        }

        jdbcAuditLogOperations.insertAuditLog(
                UUID.randomUUID(),
                organizationId,
                actorUserId,
                AuditAction.ORDER_CREATED,
                AuditEntityType.ORDER,
                orderId,
                null,
                afterState,
                metadata
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logOrderCreated(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId,
            String orderNumber,
            BigDecimal totalAmount,
            int lineItemCount
    ) {
        logOrderCreated(organizationId, actorUserId, orderId, orderNumber, "USD", totalAmount, lineItemCount, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logOrderConfirmed(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId,
            String orderNumber,
            UUID reservationId
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for user-initiated order confirmation");
        Objects.requireNonNull(orderId, "orderId must not be null");

        Map<String, Object> beforeState = Map.of("status", "PENDING");
        Map<String, Object> afterState = Map.of("status", "CONFIRMED");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderNumber", orderNumber);
        if (reservationId != null) {
            metadata.put("reservationId", reservationId.toString());
        }

        jdbcAuditLogOperations.insertAuditLog(
                UUID.randomUUID(),
                organizationId,
                actorUserId,
                AuditAction.ORDER_CONFIRMED,
                AuditEntityType.ORDER,
                orderId,
                beforeState,
                afterState,
                metadata
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logOrderConfirmed(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId,
            String orderNumber
    ) {
        logOrderConfirmed(organizationId, actorUserId, orderId, orderNumber, null);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logOrderCancelled(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId,
            String orderNumber,
            String previousStatus,
            String reason,
            boolean hadReservation
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for user-initiated order cancellation");
        Objects.requireNonNull(orderId, "orderId must not be null");

        Map<String, Object> beforeState = Map.of("status", previousStatus != null ? previousStatus : "PENDING");
        Map<String, Object> afterState = Map.of("status", "CANCELLED");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderNumber", orderNumber);
        metadata.put("hadReservation", hadReservation);
        if (reason != null && !reason.isBlank()) {
            metadata.put("reason", reason);
        }

        jdbcAuditLogOperations.insertAuditLog(
                UUID.randomUUID(),
                organizationId,
                actorUserId,
                AuditAction.ORDER_CANCELLED,
                AuditEntityType.ORDER,
                orderId,
                beforeState,
                afterState,
                metadata
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logOrderCancelled(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId,
            String orderNumber,
            String reason
    ) {
        logOrderCancelled(organizationId, actorUserId, orderId, orderNumber, "PENDING", reason, false);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logOrderFulfilled(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId,
            String orderNumber,
            UUID reservationId,
            UUID transactionId
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for user-initiated order fulfillment");
        Objects.requireNonNull(orderId, "orderId must not be null");

        Map<String, Object> beforeState = Map.of("status", "CONFIRMED");
        Map<String, Object> afterState = Map.of("status", "FULFILLED");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderNumber", orderNumber);
        if (reservationId != null) {
            metadata.put("reservationId", reservationId.toString());
        }
        if (transactionId != null) {
            metadata.put("transactionId", transactionId.toString());
        }

        jdbcAuditLogOperations.insertAuditLog(
                UUID.randomUUID(),
                organizationId,
                actorUserId,
                AuditAction.ORDER_FULFILLED,
                AuditEntityType.ORDER,
                orderId,
                beforeState,
                afterState,
                metadata
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logOrderFulfilled(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId,
            String orderNumber
    ) {
        logOrderFulfilled(organizationId, actorUserId, orderId, orderNumber, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logReservationCreated(
            UUID organizationId,
            UUID actorUserId,
            UUID reservationId,
            UUID orderId,
            int allocationCount,
            long totalQuantity,
            Instant expiresAt,
            UUID transactionId
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for user-initiated reservation creation");
        Objects.requireNonNull(reservationId, "reservationId must not be null");

        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("status", "ACTIVE");
        afterState.put("allocationCount", allocationCount);
        afterState.put("totalQuantity", totalQuantity);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderId", orderId.toString());
        metadata.put("allocationCount", allocationCount);
        metadata.put("totalQuantity", totalQuantity);
        if (expiresAt != null) {
            metadata.put("expiresAt", expiresAt.toString());
        }
        if (transactionId != null) {
            metadata.put("transactionId", transactionId.toString());
        }

        jdbcAuditLogOperations.insertAuditLog(
                UUID.randomUUID(),
                organizationId,
                actorUserId,
                AuditAction.RESERVATION_CREATED,
                AuditEntityType.RESERVATION,
                reservationId,
                null,
                afterState,
                metadata
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logReservationCreated(
            UUID organizationId,
            UUID actorUserId,
            UUID reservationId,
            UUID orderId,
            int allocationCount,
            long totalQuantity
    ) {
        logReservationCreated(organizationId, actorUserId, reservationId, orderId, allocationCount, totalQuantity, null, null);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logReservationReleased(
            UUID organizationId,
            UUID actorUserId,
            UUID reservationId,
            UUID orderId,
            String reason,
            UUID transactionId
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for user-initiated reservation release");
        Objects.requireNonNull(reservationId, "reservationId must not be null");

        Map<String, Object> beforeState = Map.of("status", "ACTIVE");
        Map<String, Object> afterState = Map.of("status", "RELEASED");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderId", orderId.toString());
        if (reason != null && !reason.isBlank()) {
            metadata.put("reason", reason);
        }
        if (transactionId != null) {
            metadata.put("transactionId", transactionId.toString());
        }

        jdbcAuditLogOperations.insertAuditLog(
                UUID.randomUUID(),
                organizationId,
                actorUserId,
                AuditAction.RESERVATION_RELEASED,
                AuditEntityType.RESERVATION,
                reservationId,
                beforeState,
                afterState,
                metadata
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logReservationReleased(
            UUID organizationId,
            UUID actorUserId,
            UUID reservationId,
            UUID orderId,
            String reason
    ) {
        logReservationReleased(organizationId, actorUserId, reservationId, orderId, reason, null);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logReservationConsumed(
            UUID organizationId,
            UUID actorUserId,
            UUID reservationId,
            UUID orderId,
            UUID transactionId
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for user-initiated reservation consumption");
        Objects.requireNonNull(reservationId, "reservationId must not be null");

        Map<String, Object> beforeState = Map.of("status", "ACTIVE");
        Map<String, Object> afterState = Map.of("status", "CONSUMED");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderId", orderId.toString());
        if (transactionId != null) {
            metadata.put("transactionId", transactionId.toString());
        }

        jdbcAuditLogOperations.insertAuditLog(
                UUID.randomUUID(),
                organizationId,
                actorUserId,
                AuditAction.RESERVATION_CONSUMED,
                AuditEntityType.RESERVATION,
                reservationId,
                beforeState,
                afterState,
                metadata
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logReservationExpired(
            UUID organizationId,
            UUID reservationId,
            UUID orderId,
            UUID transactionId
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(reservationId, "reservationId must not be null");

        Map<String, Object> beforeState = Map.of("status", "ACTIVE");
        Map<String, Object> afterState = Map.of("status", "EXPIRED");

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("orderId", orderId.toString());
        metadata.put("trigger", "SYSTEM_BACKGROUND_WORKER");
        if (transactionId != null) {
            metadata.put("transactionId", transactionId.toString());
        }

        // System actor is explicitly null per schema allowance for background worker operations
        jdbcAuditLogOperations.insertAuditLog(
                UUID.randomUUID(),
                organizationId,
                null,
                AuditAction.RESERVATION_EXPIRED,
                AuditEntityType.RESERVATION,
                reservationId,
                beforeState,
                afterState,
                metadata
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logReservationExpired(
            UUID organizationId,
            UUID reservationId,
            UUID orderId
    ) {
        logReservationExpired(organizationId, reservationId, orderId, null);
    }

    // =========================================================================
    // 3. MEMBERSHIP & TENANT ADMINISTRATION AUDIT METHODS (Propagation.REQUIRED)
    // =========================================================================

    @Transactional(propagation = Propagation.REQUIRED)
    public void logMemberAdded(
            UUID organizationId,
            UUID actorUserId,
            UUID targetUserId,
            String assignedRole,
            boolean reactivated
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for user-initiated member addition");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(assignedRole, "assignedRole must not be null");

        Map<String, Object> beforeState = reactivated ? Map.of("active", false) : null;
        Map<String, Object> afterState = Map.of("active", true, "role", assignedRole);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetUserId", targetUserId.toString());
        metadata.put("assignedRole", assignedRole);
        metadata.put("reactivated", reactivated);

        jdbcAuditLogOperations.insertAuditLog(
                UUID.randomUUID(),
                organizationId,
                actorUserId,
                AuditAction.MEMBERSHIP_CREATED,
                AuditEntityType.ORGANIZATION_MEMBERSHIP,
                targetUserId,
                beforeState,
                afterState,
                metadata
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logMemberAdded(
            UUID organizationId,
            UUID actorUserId,
            UUID targetUserId,
            String assignedRole
    ) {
        logMemberAdded(organizationId, actorUserId, targetUserId, assignedRole, false);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logMemberRoleUpdated(
            UUID organizationId,
            UUID actorUserId,
            UUID targetUserId,
            String oldRole,
            String newRole
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for user-initiated role update");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");
        Objects.requireNonNull(oldRole, "oldRole must not be null");
        Objects.requireNonNull(newRole, "newRole must not be null");

        Map<String, Object> beforeState = Map.of("role", oldRole);
        Map<String, Object> afterState = Map.of("role", newRole);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetUserId", targetUserId.toString());
        metadata.put("oldRole", oldRole);
        metadata.put("newRole", newRole);

        jdbcAuditLogOperations.insertAuditLog(
                UUID.randomUUID(),
                organizationId,
                actorUserId,
                AuditAction.MEMBERSHIP_ROLE_UPDATED,
                AuditEntityType.ORGANIZATION_MEMBERSHIP,
                targetUserId,
                beforeState,
                afterState,
                metadata
        );
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void logMemberDeactivated(
            UUID organizationId,
            UUID actorUserId,
            UUID targetUserId
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for user-initiated member deactivation");
        Objects.requireNonNull(targetUserId, "targetUserId must not be null");

        Map<String, Object> beforeState = Map.of("active", true);
        Map<String, Object> afterState = Map.of("active", false);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetUserId", targetUserId.toString());
        metadata.put("active", false);

        jdbcAuditLogOperations.insertAuditLog(
                UUID.randomUUID(),
                organizationId,
                actorUserId,
                AuditAction.MEMBERSHIP_DEACTIVATED,
                AuditEntityType.ORGANIZATION_MEMBERSHIP,
                targetUserId,
                beforeState,
                afterState,
                metadata
        );
    }

    // =========================================================================
    // 4. RECONCILIATION AUDIT METHOD (Propagation.REQUIRES_NEW)
    // =========================================================================

    /**
     * Records a scheduled reconciliation discrepancy audit event in an independent transaction.
     * Capped at {@value #MAX_DISCREPANCY_ITEMS} items to prevent storage bloat.
     */
    public void logReconciliationDiscrepancy(
            UUID organizationId,
            int totalInspected,
            int matchCount,
            int totalDiscrepancies,
            List<Map<String, Object>> discrepancyItems
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("totalInspected", totalInspected);
        metadata.put("matchCount", matchCount);
        metadata.put("totalDiscrepancyCount", totalDiscrepancies);

        boolean truncated = discrepancyItems != null && discrepancyItems.size() > MAX_DISCREPANCY_ITEMS;
        metadata.put("truncated", truncated);
        metadata.put("maxRecordedItems", MAX_DISCREPANCY_ITEMS);

        if (discrepancyItems != null && !discrepancyItems.isEmpty()) {
            List<Map<String, Object>> boundedItems = truncated
                    ? discrepancyItems.subList(0, MAX_DISCREPANCY_ITEMS)
                    : discrepancyItems;
            metadata.put("items", boundedItems);
        } else {
            metadata.put("items", Collections.emptyList());
        }

        // Executes in an independent transaction via Spring bean proxy
        transactionDelegate.writeInNewTransaction(
                organizationId,
                null, // System actor
                AuditAction.RECONCILIATION_DISCREPANCY_DETECTED,
                AuditEntityType.RECONCILIATION,
                null,
                null,
                null,
                metadata
        );
    }

    // =========================================================================
    // 5. INDEPENDENT SECURITY AUDIT METHODS (Propagation.REQUIRES_NEW)
    // =========================================================================

    /**
     * Records an authenticated session revocation event in an independent transaction.
     */
    public void logSessionRevoked(
            UUID organizationId,
            UUID actorUserId,
            UUID sessionId,
            String reason
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sessionId", sessionId.toString());
        metadata.put("reason", reason);

        transactionDelegate.writeInNewTransaction(
                organizationId,
                actorUserId,
                AuditAction.SESSION_REVOKED,
                AuditEntityType.AUTH_SESSION,
                sessionId,
                null,
                null,
                metadata
        );
    }

    /**
     * Records an authenticated refresh token replay detection event in an independent transaction.
     */
    public void logRefreshTokenReplayDetected(
            UUID organizationId,
            UUID actorUserId,
            UUID sessionId,
            int sequenceNumber
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("sessionId", sessionId.toString());
        metadata.put("sequenceNumber", sequenceNumber);

        transactionDelegate.writeInNewTransaction(
                organizationId,
                actorUserId,
                AuditAction.REFRESH_TOKEN_REPLAY_DETECTED,
                AuditEntityType.AUTH_SESSION,
                sessionId,
                null,
                null,
                metadata
        );
    }

    /**
     * Records an authenticated organization switch event in an independent transaction.
     */
    public void logOrganizationSwitched(
            UUID targetOrganizationId,
            UUID actorUserId,
            String targetRole
    ) {
        Objects.requireNonNull(targetOrganizationId, "targetOrganizationId must not be null");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("targetRole", targetRole);

        transactionDelegate.writeInNewTransaction(
                targetOrganizationId,
                actorUserId,
                AuditAction.ORGANIZATION_SWITCHED,
                AuditEntityType.ORGANIZATION_MEMBERSHIP,
                targetOrganizationId,
                null,
                null,
                metadata
        );
    }

    /**
     * Records an authenticated authorization failure in an independent transaction.
     */
    public void logAccessDenied(
            UUID organizationId,
            UUID actorUserId,
            String resource,
            String reason
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null");
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (resource != null) {
            metadata.put("resource", resource);
        }
        if (reason != null) {
            metadata.put("reason", reason);
        }

        transactionDelegate.writeInNewTransaction(
                organizationId,
                actorUserId,
                AuditAction.ACCESS_DENIED,
                AuditEntityType.AUTH_SESSION,
                actorUserId,
                null,
                null,
                metadata
        );
    }

    // =========================================================================
    // 6. READ OPERATIONS (Read-Only)
    // =========================================================================

    @Transactional(readOnly = true)
    public Optional<AuditLog> findByIdAndOrganizationId(UUID id, UUID organizationId) {
        return jdbcAuditLogOperations.findById(organizationId, id);
    }

    @Transactional(readOnly = true)
    public List<AuditLog> findAuditLogs(
            UUID organizationId,
            String entityType,
            UUID entityId,
            String action,
            UUID actorUserId,
            Instant fromDate,
            Instant toDate,
            int limit,
            int offset
    ) {
        return jdbcAuditLogOperations.findAuditLogs(
                organizationId,
                entityType,
                entityId,
                action,
                actorUserId,
                fromDate,
                toDate,
                limit,
                offset
        );
    }

    @Transactional(readOnly = true)
    public List<AuditLog> findAuditLogs(
            UUID organizationId,
            String entityType,
            String action,
            UUID actorUserId,
            Instant fromDate,
            Instant toDate,
            int limit,
            int offset
    ) {
        return findAuditLogs(
                organizationId,
                entityType,
                null,
                action,
                actorUserId,
                fromDate,
                toDate,
                limit,
                offset
        );
    }

    @Transactional(readOnly = true)
    public long countAuditLogs(
            UUID organizationId,
            String entityType,
            UUID entityId,
            String action,
            UUID actorUserId,
            Instant fromDate,
            Instant toDate
    ) {
        return jdbcAuditLogOperations.countAuditLogs(
                organizationId,
                entityType,
                entityId,
                action,
                actorUserId,
                fromDate,
                toDate
        );
    }

    @Transactional(readOnly = true)
    public long countAuditLogs(
            UUID organizationId,
            String entityType,
            String action,
            UUID actorUserId,
            Instant fromDate,
            Instant toDate
    ) {
        return countAuditLogs(
                organizationId,
                entityType,
                null,
                action,
                actorUserId,
                fromDate,
                toDate
        );
    }
}
