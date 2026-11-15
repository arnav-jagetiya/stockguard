package com.stockguard.inventory.service;

import com.stockguard.audit.service.AuditLogService;
import com.stockguard.inventory.domain.DiscrepancyStatus;
import com.stockguard.inventory.domain.DiscrepancyType;
import com.stockguard.inventory.dto.DiscrepancySyncResult;
import com.stockguard.inventory.dto.ReconciliationDiscrepancy;
import com.stockguard.inventory.dto.ReconciliationReport;
import com.stockguard.inventory.repository.JdbcDiscrepancyOperations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Service managing discrepancy persistence, repeated observation tracking, and automatic resolution per ADR-012.
 * Executes in Tx2 under Propagation.REQUIRED to commit atomically with audit log records.
 */
@Service
@Profile("!test")
public class DiscrepancyManagementService {

    private static final Logger log = LoggerFactory.getLogger(DiscrepancyManagementService.class);
    private static final String AUTO_RESOLUTION_REASON = "System auto-resolved: balance and ledger matched during scheduled sweep";

    private final JdbcDiscrepancyOperations jdbcDiscrepancyOperations;
    private final AuditLogService auditLogService;

    public DiscrepancyManagementService(
            JdbcDiscrepancyOperations jdbcDiscrepancyOperations,
            AuditLogService auditLogService
    ) {
        this.jdbcDiscrepancyOperations = Objects.requireNonNull(jdbcDiscrepancyOperations, "JdbcDiscrepancyOperations must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService, "AuditLogService must not be null");
    }

    /**
     * Synchronizes detected discrepancies and updates existing active discrepancy states for an organization.
     * <p>
     * Transaction: Tx2 executing with {@code Propagation.REQUIRED}.
     * Discrepancy mutations and audit log records commit atomically.
     *
     * @param organizationId tenant organization UUID
     * @param report reconciliation report from Tx1
     * @return synchronization outcome summary
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public DiscrepancySyncResult syncDiscrepancies(UUID organizationId, ReconciliationReport report) {
        Objects.requireNonNull(organizationId, "Organization ID must not be null");
        Objects.requireNonNull(report, "ReconciliationReport must not be null");

        int newDiscrepancies = 0;
        int repeatedObservations = 0;
        int autoResolved = 0;
        int parityVerifiedPreserved = 0;

        Set<String> detectedPositions = new HashSet<>();
        List<Map<String, Object>> auditItems = new ArrayList<>();

        // 1. Process all detected mismatches in current sweep
        if (report.discrepancies() != null) {
            for (ReconciliationDiscrepancy item : report.discrepancies()) {
                String positionKey = item.productId() + ":" + item.warehouseId();
                detectedPositions.add(positionKey);

                DiscrepancyType type = DiscrepancyType.valueOf(item.discrepancyType());

                ReconciliationReport.SnapshotActiveDiscrepancy snapshotDisc = report.snapshotActiveDiscrepanciesByPosition() != null
                        ? report.snapshotActiveDiscrepanciesByPosition().get(positionKey)
                        : null;
                UUID expectedId = snapshotDisc != null ? snapshotDisc.id() : null;
                Integer expectedVersion = snapshotDisc != null ? snapshotDisc.version() : null;
                boolean allowUnconditionalUpdate = (report.snapshotActiveDiscrepanciesByPosition() == null);

                JdbcDiscrepancyOperations.UpsertResult upsert = jdbcDiscrepancyOperations.upsertActiveDiscrepancy(
                        organizationId,
                        item.productId(),
                        item.warehouseId(),
                        type,
                        item.balanceOnHand(),
                        item.ledgerOnHandSum(),
                        item.balanceReserved(),
                        item.ledgerReservedSum(),
                        expectedId,
                        expectedVersion,
                        allowUnconditionalUpdate
                );

                if (upsert == null) {
                    log.warn("Skipping upsert for position [{}]: existing discrepancy cannot be updated (version advanced, resolved, or concurrently active)", positionKey);
                    continue;
                }

                if (upsert.isNewInsert()) {
                    newDiscrepancies++;
                    log.info("Recorded new active discrepancy [id={}, type={}, org={}, product={}, warehouse={}]",
                            upsert.id(), type, organizationId, item.productId(), item.warehouseId());
                } else {
                    repeatedObservations++;
                    log.debug("Updated repeated active discrepancy observation [id={}, status={}, count={}, org={}]",
                            upsert.id(), upsert.status(), upsert.consecutiveDetectionCount(), organizationId);
                }

                Map<String, Object> auditItem = new LinkedHashMap<>();
                auditItem.put("discrepancyId", upsert.id().toString());
                auditItem.put("productId", item.productId().toString());
                auditItem.put("warehouseId", item.warehouseId().toString());
                auditItem.put("discrepancyType", item.discrepancyType());
                auditItem.put("balanceOnHand", item.balanceOnHand());
                auditItem.put("ledgerOnHand", item.ledgerOnHandSum());
                auditItem.put("balanceReserved", item.balanceReserved());
                auditItem.put("ledgerReserved", item.ledgerReservedSum());
                auditItem.put("consecutiveCount", upsert.consecutiveDetectionCount());
                auditItems.add(auditItem);
            }
        }

        // 2. Inspect existing active discrepancies for clean parity observation
        Instant reportObservationTime = report.reconciledAt() != null ? report.reconciledAt() : Instant.now();
        List<JdbcDiscrepancyOperations.ActiveDiscrepancyRow> activeRows =
                jdbcDiscrepancyOperations.findActiveDiscrepancies(organizationId);

        for (JdbcDiscrepancyOperations.ActiveDiscrepancyRow activeRow : activeRows) {
            String positionKey = activeRow.productId() + ":" + activeRow.warehouseId();

            if (!detectedPositions.contains(positionKey)) {
                // MVCC snapshot visibility invariant: clean parity must only resolve or update
                // a discrepancy that was proven visible in Tx1's REPEATABLE READ snapshot.
                if (report.snapshotActiveDiscrepancyIds() != null && !report.snapshotActiveDiscrepancyIds().contains(activeRow.id())) {
                    log.warn("Skipping parity update for discrepancy [{}] for position [{}] because it was not visible in Tx1's REPEATABLE READ snapshot",
                            activeRow.id(), positionKey);
                    continue;
                }

                // Stale report invariant: clean parity must not resolve or update a discrepancy
                // whose last detection is more recent than the observation timestamp of this report.
                if (activeRow.lastDetectedAt() != null && activeRow.lastDetectedAt().isAfter(reportObservationTime)) {
                    log.warn("Skipping parity update for discrepancy [{}] for position [{}] because its last detection [{}] is newer than report timestamp [{}]",
                            activeRow.id(), positionKey, activeRow.lastDetectedAt(), reportObservationTime);
                    continue;
                }

                // Snapshot version guard: ensure discrepancy has not been concurrently modified
                int expectedVersion = activeRow.version();
                if (report.snapshotActiveDiscrepanciesById() != null) {
                    ReconciliationReport.SnapshotActiveDiscrepancy snap = report.snapshotActiveDiscrepanciesById().get(activeRow.id());
                    if (snap != null) {
                        expectedVersion = snap.version();
                    }
                }

                // Clean parity observed in this sweep
                if (activeRow.status() == DiscrepancyStatus.OPEN) {
                    boolean resolved = jdbcDiscrepancyOperations.autoResolveOpenDiscrepancy(
                            activeRow.id(),
                            expectedVersion,
                            AUTO_RESOLUTION_REASON,
                            reportObservationTime
                    );
                    if (resolved) {
                        autoResolved++;
                        log.info("Auto-resolved clean OPEN discrepancy [id={}, org={}, product={}, warehouse={}]",
                                activeRow.id(), organizationId, activeRow.productId(), activeRow.warehouseId());

                        // Log automatic resolution audit event inside Tx2
                        auditLogService.logReconciliationCompleted(
                                organizationId,
                                activeRow.id(),
                                AUTO_RESOLUTION_REASON
                        );
                    }
                } else if (activeRow.status() == DiscrepancyStatus.ACKNOWLEDGED || activeRow.status() == DiscrepancyStatus.INVESTIGATING) {
                    // Preserve status and investigation state, update last_verified_at
                    boolean updated = jdbcDiscrepancyOperations.updateParityVerified(
                            activeRow.id(),
                            expectedVersion,
                            reportObservationTime
                    );
                    if (updated) {
                        parityVerifiedPreserved++;
                        log.info("Clean parity observed for {} discrepancy [id={}, org={}]; preserved for human closure",
                                activeRow.status(), activeRow.id(), organizationId);
                    }
                }
            }
        }

        // 3. Atomically write reconciliation sweep audit log inside Tx2
        auditLogService.logReconciliationDiscrepancy(
                organizationId,
                report.totalBalancesInspected(),
                report.matchingBalances(),
                report.discrepantBalances(),
                auditItems
        );

        return new DiscrepancySyncResult(newDiscrepancies, repeatedObservations, autoResolved, parityVerifiedPreserved);
    }
}
