package com.stockguard.reservation.service;

import com.stockguard.audit.service.AuditLogService;
import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.repository.JdbcInventoryOperations.ProductWarehouseKey;
import com.stockguard.order.domain.OrderStatus;
import com.stockguard.order.repository.JdbcOrderOperations;
import com.stockguard.reservation.config.ReservationProperties;
import com.stockguard.reservation.domain.ReservationStatus;
import com.stockguard.reservation.dto.ExpirationBatchResult;
import com.stockguard.reservation.repository.JdbcReservationOperations;
import com.stockguard.reservation.repository.JdbcReservationOperations.ExpiredCandidateInfo;
import com.stockguard.reservation.repository.JdbcReservationOperations.LockedAllocationInfo;
import com.stockguard.reservation.repository.JdbcReservationOperations.LockedOrderInfo;
import com.stockguard.reservation.repository.JdbcReservationOperations.LockedReservationExpirationInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Service orchestrating background expiration of stale order reservations.
 * <p>
 * Key Invariants:
 * <ul>
 *   <li>Database-authoritative time: Evaluates {@code expires_at <= NOW()} using PostgreSQL.</li>
 *   <li>Non-transactional coordinator: Each candidate is processed in its own independent transaction.</li>
 *   <li>Canonical lock hierarchy: {@code orders FOR UPDATE OF o SKIP LOCKED -> reservations FOR UPDATE -> inventory_balances FOR UPDATE (product_id ASC, warehouse_id ASC)}.</li>
 *   <li>Stock effects: {@code on_hand_delta = 0, reserved_delta = -Q}.</li>
 *   <li>Terminal status transitions: Reservation becomes {@code EXPIRED}, Order becomes {@code EXPIRED}.</li>
 * </ul>
 */
@Service
public class ReservationExpirationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirationService.class);

    private final JdbcReservationOperations jdbcReservationOperations;
    private final JdbcOrderOperations jdbcOrderOperations;
    private final ReservationProperties reservationProperties;
    private final PlatformTransactionManager transactionManager;
    private final AuditLogService auditLogService;

    @Autowired
    public ReservationExpirationService(
            JdbcReservationOperations jdbcReservationOperations,
            JdbcOrderOperations jdbcOrderOperations,
            ReservationProperties reservationProperties,
            PlatformTransactionManager transactionManager,
            AuditLogService auditLogService
    ) {
        this.jdbcReservationOperations = Objects.requireNonNull(jdbcReservationOperations, "jdbcReservationOperations must not be null");
        this.jdbcOrderOperations = Objects.requireNonNull(jdbcOrderOperations, "jdbcOrderOperations must not be null");
        this.reservationProperties = Objects.requireNonNull(reservationProperties, "reservationProperties must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService, "auditLogService must not be null");
    }

    public enum CandidateResultType {
        SUCCESS,
        SKIPPED,
        FAILURE,
        NO_MORE_CANDIDATES
    }

    public record CandidateExecutionResult(
            CandidateResultType type,
            UUID orderId,
            String reason,
            Throwable error
    ) {
        public static CandidateExecutionResult success(UUID orderId) {
            return new CandidateExecutionResult(CandidateResultType.SUCCESS, orderId, null, null);
        }

        public static CandidateExecutionResult skipped(UUID orderId, String reason) {
            return new CandidateExecutionResult(CandidateResultType.SKIPPED, orderId, reason, null);
        }

        public static CandidateExecutionResult failure(UUID orderId, Throwable error) {
            return new CandidateExecutionResult(CandidateResultType.FAILURE, orderId, error != null ? error.getMessage() : "Unknown failure", error);
        }

        public static CandidateExecutionResult noMoreCandidates() {
            return new CandidateExecutionResult(CandidateResultType.NO_MORE_CANDIDATES, null, "No eligible expired candidates found", null);
        }
    }

    /**
     * Non-transactional batch sweep coordinator across all tenant organizations.
     * <p>
     * Evaluates expired candidate orders up to the configured batch size.
     * Both successfully processed, skipped, and failed candidates increment the examined counter.
     * Each candidate is processed in its own independent transaction (PROPAGATION_REQUIRES_NEW).
     */
    public ExpirationBatchResult sweepExpiredReservations() {
        return sweepExpiredReservations(null);
    }

    /**
     * Non-transactional batch sweep coordinator scoped optionally to a specific organization.
     */
    public ExpirationBatchResult sweepExpiredReservations(UUID organizationId) {
        int batchSize = reservationProperties.getExpiration().getBatchSize();
        long startTime = System.currentTimeMillis();

        int examinedCount = 0;
        int successCount = 0;
        int skippedCount = 0;
        int failureCount = 0;

        Set<UUID> excludedOrderIdsInCurrentSweep = new HashSet<>();

        while (examinedCount < batchSize) {
            CandidateExecutionResult result = processNextCandidateInTransaction(organizationId, excludedOrderIdsInCurrentSweep);

            if (result.type() == CandidateResultType.NO_MORE_CANDIDATES) {
                break;
            }

            examinedCount++;

            if (result.orderId() != null) {
                excludedOrderIdsInCurrentSweep.add(result.orderId());
            }

            switch (result.type()) {
                case SUCCESS -> successCount++;
                case SKIPPED -> skippedCount++;
                case FAILURE -> failureCount++;
                default -> {}
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;
        log.info("Reservation expiration sweep completed in {} ms: examined={}, expired={}, skipped={}, failed={}",
                durationMs, examinedCount, successCount, skippedCount, failureCount);

        return new ExpirationBatchResult(examinedCount, successCount, skippedCount, failureCount, durationMs);
    }

    /**
     * Processes a single expired candidate inside an independent database transaction.
     */
    public CandidateExecutionResult processNextCandidateInTransaction(Set<UUID> excludedOrderIds) {
        return processNextCandidateInTransaction(null, excludedOrderIds);
    }

    /**
     * Processes a single expired candidate for a specific tenant organization inside an independent database transaction.
     */
    public CandidateExecutionResult processNextCandidateInTransaction(UUID organizationId, Set<UUID> excludedOrderIds) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        final UUID[] claimedOrderId = new UUID[1];
        try {
            return template.execute(status -> {
                // 1. Select and lock eligible order row FOR UPDATE OF o SKIP LOCKED
                Optional<ExpiredCandidateInfo> candidateOpt =
                        jdbcReservationOperations.findNextExpiredCandidate(organizationId, excludedOrderIds);

                if (candidateOpt.isEmpty()) {
                    return CandidateExecutionResult.noMoreCandidates();
                }

                ExpiredCandidateInfo candidate = candidateOpt.get();
                UUID orderId = candidate.orderId();
                claimedOrderId[0] = orderId;
                UUID candidateOrgId = candidate.organizationId();
                UUID reservationId = candidate.reservationId();

                return executeExpirationUnderLocks(candidateOrgId, orderId, reservationId);
            });
        } catch (Exception ex) {
            log.error("Transactional failure processing expired reservation candidate {}: {}", claimedOrderId[0], ex.getMessage(), ex);
            return CandidateExecutionResult.failure(claimedOrderId[0], ex);
        }
    }

    /**
     * Executes expiration for a specific candidate order by ID in an independent transaction.
     */
    public boolean expireSpecificCandidate(UUID organizationId, UUID orderId) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        CandidateExecutionResult result = template.execute(status -> {
            Optional<ExpiredCandidateInfo> candidateOpt =
                    jdbcReservationOperations.findExpiredCandidateForOrder(organizationId, orderId);

            if (candidateOpt.isEmpty()) {
                return CandidateExecutionResult.skipped(orderId, "Order is not an expired pending reservation candidate");
            }

            ExpiredCandidateInfo candidate = candidateOpt.get();
            return executeExpirationUnderLocks(organizationId, orderId, candidate.reservationId());
        });

        return result != null && result.type() == CandidateResultType.SUCCESS;
    }

    private CandidateExecutionResult executeExpirationUnderLocks(
            UUID organizationId,
            UUID orderId,
            UUID reservationId
    ) {
        // 2. Lock reservation row FOR UPDATE
        Optional<LockedReservationExpirationInfo> resOpt =
                jdbcReservationOperations.lockReservationForExpiration(organizationId, reservationId);

        if (resOpt.isEmpty()) {
            return CandidateExecutionResult.skipped(orderId, "Reservation not found");
        }

        LockedReservationExpirationInfo res = resOpt.get();

        // 3. Revalidate state under lock:
        // - Order is PENDING
        // - Reservation is ACTIVE
        // - expires_at IS NOT NULL
        // - expires_at <= NOW()
        Optional<LockedOrderInfo> orderOpt = jdbcReservationOperations.lockOrder(organizationId, orderId);
        if (orderOpt.isEmpty() || orderOpt.get().status() != OrderStatus.PENDING) {
            String currStatus = orderOpt.map(o -> o.status().name()).orElse("NOT_FOUND");
            log.debug("Candidate order {} skipped: order is not in PENDING status (status={})", orderId, currStatus);
            return CandidateExecutionResult.skipped(orderId, "Order is no longer PENDING: " + currStatus);
        }

        if (!"ACTIVE".equals(res.status())) {
            log.debug("Candidate order {} skipped: reservation {} is not ACTIVE (status={})", orderId, reservationId, res.status());
            return CandidateExecutionResult.skipped(orderId, "Reservation is no longer ACTIVE: " + res.status());
        }

        if (res.expiresAt() == null || !res.isExpired()) {
            log.debug("Candidate order {} skipped: reservation {} is not expired (expiresAt={}, isExpired={})",
                    orderId, reservationId, res.expiresAt(), res.isExpired());
            return CandidateExecutionResult.skipped(orderId, "Reservation is not expired");
        }

        // 4. Retrieve allocations
        List<LockedAllocationInfo> allocations =
                jdbcReservationOperations.getReservationAllocations(organizationId, res.id());

        UUID releaseTxId = null;
        if (!allocations.isEmpty()) {
            // Aggregate quantities by product-warehouse
            Map<ProductWarehouseKey, Long> totalReservedByPw = new LinkedHashMap<>();
            Set<UUID> distinctProductIds = new TreeSet<>();
            for (LockedAllocationInfo a : allocations) {
                ProductWarehouseKey key = new ProductWarehouseKey(a.productId(), a.warehouseId());
                totalReservedByPw.merge(key, a.quantity(), Long::sum);
                distinctProductIds.add(a.productId());
            }

            // 5. Lock balances in canonical order: product_id ASC, warehouse_id ASC
            List<InventoryBalance> lockedBalances = jdbcReservationOperations.lockAndGetExistingBalances(
                    organizationId, new ArrayList<>(distinctProductIds)
            );
            Map<ProductWarehouseKey, InventoryBalance> balanceMap = new HashMap<>();
            for (InventoryBalance b : lockedBalances) {
                balanceMap.put(new ProductWarehouseKey(b.getProductId(), b.getWarehouseId()), b);
            }

            // Verify allocated balances exist and have sufficient reserved stock
            for (Map.Entry<ProductWarehouseKey, Long> entry : totalReservedByPw.entrySet()) {
                ProductWarehouseKey key = entry.getKey();
                long releaseQty = entry.getValue();
                InventoryBalance balance = balanceMap.get(key);
                if (balance == null) {
                    throw new IllegalStateException("Missing inventory balance for allocated product " + key.productId());
                }
                if (balance.getReserved() < releaseQty) {
                    throw new IllegalStateException("Reserved balance for product " + key.productId() +
                            " in warehouse " + key.warehouseId() + " (" + balance.getReserved() +
                            ") is less than allocation release quantity: " + releaseQty);
                }
            }

            // 6. Insert inventory transaction header (RELEASE)
            UUID txId = UUID.randomUUID();
            releaseTxId = txId;
            jdbcReservationOperations.insertInventoryTransaction(
                    txId, organizationId, "RELEASE", "ORDER", orderId, "Reservation expired for order " + orderId, null
            );

            // 7. Update balances and insert ledger entries in canonical order
            List<ProductWarehouseKey> sortedKeys = new ArrayList<>(totalReservedByPw.keySet());
            Collections.sort(sortedKeys);

            int entryNumber = 1;
            for (ProductWarehouseKey key : sortedKeys) {
                long releaseQty = totalReservedByPw.get(key);
                InventoryBalance balance = balanceMap.get(key);

                jdbcReservationOperations.updateInventoryBalanceRelease(balance.getId(), organizationId, releaseQty);
                jdbcReservationOperations.insertInventoryLedgerEntry(
                        UUID.randomUUID(), organizationId, txId, entryNumber++, key.productId(), key.warehouseId(), 0L, -releaseQty
                );
            }
        }

        // 8. Mark reservation EXPIRED
        jdbcReservationOperations.updateReservationStatus(organizationId, res.id(), ReservationStatus.EXPIRED);

        // 9. Mark order EXPIRED
        jdbcOrderOperations.updateOrderStatus(organizationId, orderId, OrderStatus.EXPIRED);

        // 10. Audit logging using system actor semantics (actor is null for background worker)
        auditLogService.logReservationExpired(organizationId, res.id(), orderId, releaseTxId);

        log.debug("Successfully expired order {} and reservation {}", orderId, res.id());
        return CandidateExecutionResult.success(orderId);
    }
}
