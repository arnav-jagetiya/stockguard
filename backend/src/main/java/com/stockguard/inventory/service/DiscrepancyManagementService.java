package com.stockguard.inventory.service;

import com.stockguard.audit.domain.AuditAction;
import com.stockguard.audit.domain.AuditEntityType;
import com.stockguard.audit.service.AuditLogService;
import com.stockguard.infrastructure.metrics.StockGuardMetrics;
import com.stockguard.inventory.domain.DiscrepancyStatus;
import com.stockguard.inventory.domain.DiscrepancyType;
import com.stockguard.inventory.domain.ReconciliationDiscrepancyEntity;
import com.stockguard.inventory.domain.ResolutionSource;
import com.stockguard.inventory.dto.DiscrepancyPageResponse;
import com.stockguard.inventory.dto.DiscrepancyResponse;
import com.stockguard.inventory.dto.DiscrepancySyncResult;
import com.stockguard.inventory.dto.ReconciliationDiscrepancy;
import com.stockguard.inventory.dto.ReconciliationReport;
import com.stockguard.inventory.dto.UpdateDiscrepancyRequest;
import com.stockguard.inventory.repository.JdbcDiscrepancyOperations;
import com.stockguard.inventory.repository.ReconciliationDiscrepancyRepository;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Permission;
import com.stockguard.organization.domain.RolePermissions;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.shared.exception.InvalidOperationException;
import com.stockguard.shared.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.OptimisticLockingFailureException;
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
 * Service managing discrepancy persistence, repeated observation tracking, lifecycle transitions,
 * and automatic resolution per ADR-012 and Milestone 6 Phase 2 specifications.
 */
@Service
@Profile("!test")
public class DiscrepancyManagementService {

    private static final Logger log = LoggerFactory.getLogger(DiscrepancyManagementService.class);
    private static final String AUTO_RESOLUTION_REASON = "System auto-resolved: balance and ledger matched during scheduled sweep";

    private final JdbcDiscrepancyOperations jdbcDiscrepancyOperations;
    private final ReconciliationDiscrepancyRepository discrepancyRepository;
    private final OrganizationMembershipRepository organizationMembershipRepository;
    private final AuditLogService auditLogService;
    private final StockGuardMetrics metrics;

    public DiscrepancyManagementService(
            JdbcDiscrepancyOperations jdbcDiscrepancyOperations,
            ReconciliationDiscrepancyRepository discrepancyRepository,
            OrganizationMembershipRepository organizationMembershipRepository,
            AuditLogService auditLogService
    ) {
        this(jdbcDiscrepancyOperations, discrepancyRepository, organizationMembershipRepository, auditLogService, null);
    }

    @Autowired
    public DiscrepancyManagementService(
            JdbcDiscrepancyOperations jdbcDiscrepancyOperations,
            ReconciliationDiscrepancyRepository discrepancyRepository,
            OrganizationMembershipRepository organizationMembershipRepository,
            AuditLogService auditLogService,
            @Autowired(required = false) StockGuardMetrics metrics
    ) {
        this.jdbcDiscrepancyOperations = Objects.requireNonNull(jdbcDiscrepancyOperations, "JdbcDiscrepancyOperations must not be null");
        this.discrepancyRepository = Objects.requireNonNull(discrepancyRepository, "ReconciliationDiscrepancyRepository must not be null");
        this.organizationMembershipRepository = Objects.requireNonNull(organizationMembershipRepository, "OrganizationMembershipRepository must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService, "AuditLogService must not be null");
        this.metrics = metrics;
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

                if (metrics != null) {
                    metrics.recordDiscrepancyDetected(type);
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

    /**
     * Lists discrepancies matching filters with deterministic ordering and pagination.
     */
    @Transactional(readOnly = true)
    public DiscrepancyPageResponse listDiscrepancies(
            UUID organizationId,
            DiscrepancyStatus status,
            UUID productId,
            UUID warehouseId,
            UUID assignedToUserId,
            int limit,
            int offset
    ) {
        Objects.requireNonNull(organizationId, "Organization ID must not be null");
        int boundedLimit = Math.min(Math.max(limit, 1), 100);
        int boundedOffset = Math.max(offset, 0);

        List<DiscrepancyResponse> items = jdbcDiscrepancyOperations.findDiscrepancies(
                organizationId, status, productId, warehouseId, assignedToUserId, boundedLimit, boundedOffset
        );
        long totalCount = jdbcDiscrepancyOperations.countDiscrepancies(
                organizationId, status, productId, warehouseId, assignedToUserId
        );

        return new DiscrepancyPageResponse(items, totalCount, boundedLimit, boundedOffset);
    }

    /**
     * Retrieves a single discrepancy by organization and ID.
     */
    @Transactional(readOnly = true)
    public DiscrepancyResponse getDiscrepancy(UUID organizationId, UUID discrepancyId) {
        Objects.requireNonNull(organizationId, "Organization ID must not be null");
        Objects.requireNonNull(discrepancyId, "Discrepancy ID must not be null");

        return jdbcDiscrepancyOperations.findDiscrepancyById(organizationId, discrepancyId)
                .orElseThrow(() -> new ResourceNotFoundException("Discrepancy not found: " + discrepancyId));
    }

    /**
     * Updates discrepancy status, assignment, and notes per the authoritative lifecycle transition matrix.
     * Enforces optimistic locking, active membership verification, and atomic audit logging.
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public DiscrepancyResponse updateDiscrepancy(
            UUID organizationId,
            UUID actorUserId,
            UUID discrepancyId,
            UpdateDiscrepancyRequest request
    ) {
        Objects.requireNonNull(organizationId, "Organization ID must not be null");
        Objects.requireNonNull(discrepancyId, "Discrepancy ID must not be null");
        Objects.requireNonNull(request, "UpdateDiscrepancyRequest must not be null");

        ReconciliationDiscrepancyEntity entity = discrepancyRepository.findByIdAndOrganizationId(discrepancyId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Discrepancy not found: " + discrepancyId));

        // 1. Optimistic Locking Check
        if (request.version() == null || !Objects.equals(request.version(), entity.getVersion())) {
            throw new OptimisticLockingFailureException("Version conflict for discrepancy " + discrepancyId
                    + ": expected " + entity.getVersion() + " but was " + request.version());
        }

        // 2. Terminal State Immutability
        DiscrepancyStatus currentStatus = entity.getStatus();
        if (currentStatus == DiscrepancyStatus.RESOLVED || currentStatus == DiscrepancyStatus.REJECTED) {
            throw new InvalidOperationException("Discrepancy is in terminal status " + currentStatus + " and cannot be modified");
        }

        DiscrepancyStatus targetStatus = request.status() != null ? request.status() : currentStatus;

        // 3. Authoritative Lifecycle Transition Matrix Validation
        validateStatusTransition(currentStatus, targetStatus);

        // Capture before state for audit logging
        Map<String, Object> beforeState = new LinkedHashMap<>();
        beforeState.put("status", currentStatus.name());
        beforeState.put("assignedToUserId", entity.getAssignedToUserId() != null ? entity.getAssignedToUserId().toString() : null);
        beforeState.put("investigationNotes", entity.getInvestigationNotes());
        beforeState.put("version", entity.getVersion());

        // 4. Resolution / Rejection Justification Validation
        if (targetStatus == DiscrepancyStatus.RESOLVED || targetStatus == DiscrepancyStatus.REJECTED) {
            if (request.resolutionReason() == null || request.resolutionReason().trim().isEmpty()
                    || !request.resolutionReason().matches(".*\\S.*")) {
                throw new IllegalArgumentException("Resolution reason is required and must contain non-whitespace characters when resolving or rejecting");
            }
            entity.setResolutionSource(ResolutionSource.HUMAN);
            entity.setResolvedByUserId(actorUserId);
            entity.setResolvedAt(Instant.now());
            entity.setResolutionReason(request.resolutionReason().trim());
        }

        // 5. Assignment & Membership Validation
        UUID currentAssignee = entity.getAssignedToUserId();
        UUID requestedAssignee = request.assignedToUserId();

        if (targetStatus == DiscrepancyStatus.INVESTIGATING) {
            UUID targetAssignee = requestedAssignee != null ? requestedAssignee : currentAssignee;
            if (targetAssignee == null) {
                throw new IllegalArgumentException("Assigned user ID is required when transitioning to or investigating discrepancy");
            }
            validateAssigneeMembership(organizationId, targetAssignee);
            entity.setAssignedToUserId(targetAssignee);
        } else if (targetStatus == DiscrepancyStatus.ACKNOWLEDGED && currentStatus == DiscrepancyStatus.INVESTIGATING) {
            // Re-queued to general unassigned pool
            entity.setAssignedToUserId(null);
        } else if (requestedAssignee != null) {
            validateAssigneeMembership(organizationId, requestedAssignee);
            entity.setAssignedToUserId(requestedAssignee);
        }

        // 6. Apply Notes and Status
        if (request.investigationNotes() != null) {
            entity.setInvestigationNotes(request.investigationNotes());
        }
        entity.setStatus(targetStatus);
        entity.setUpdatedAt(Instant.now());

        // Save entity (bumping version)
        ReconciliationDiscrepancyEntity saved = discrepancyRepository.saveAndFlush(entity);

        // 7. Determine Audit Action and Emit Log
        String auditAction = determineAuditAction(currentStatus, targetStatus, currentAssignee, saved.getAssignedToUserId());

        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("status", saved.getStatus().name());
        afterState.put("assignedToUserId", saved.getAssignedToUserId() != null ? saved.getAssignedToUserId().toString() : null);
        afterState.put("investigationNotes", saved.getInvestigationNotes());
        afterState.put("version", saved.getVersion());
        if (saved.getResolutionSource() != null) {
            afterState.put("resolutionSource", saved.getResolutionSource().name());
            afterState.put("resolvedByUserId", saved.getResolvedByUserId() != null ? saved.getResolvedByUserId().toString() : null);
            afterState.put("resolvedAt", saved.getResolvedAt() != null ? saved.getResolvedAt().toString() : null);
            afterState.put("resolutionReason", saved.getResolutionReason());
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("discrepancyType", saved.getDiscrepancyType().name());
        metadata.put("productId", saved.getProductId().toString());
        metadata.put("warehouseId", saved.getWarehouseId().toString());

        auditLogService.logDiscrepancyLifecycleEvent(
                organizationId,
                actorUserId,
                auditAction,
                discrepancyId,
                beforeState,
                afterState,
                metadata
        );

        return jdbcDiscrepancyOperations.findDiscrepancyById(organizationId, discrepancyId)
                .orElseThrow(() -> new ResourceNotFoundException("Discrepancy not found after update: " + discrepancyId));
    }

    private void validateStatusTransition(DiscrepancyStatus current, DiscrepancyStatus target) {
        if (current == target) {
            return;
        }

        boolean valid = switch (current) {
            case OPEN -> target == DiscrepancyStatus.ACKNOWLEDGED
                    || target == DiscrepancyStatus.INVESTIGATING
                    || target == DiscrepancyStatus.RESOLVED
                    || target == DiscrepancyStatus.REJECTED;
            case ACKNOWLEDGED -> target == DiscrepancyStatus.INVESTIGATING
                    || target == DiscrepancyStatus.RESOLVED
                    || target == DiscrepancyStatus.REJECTED;
            case INVESTIGATING -> target == DiscrepancyStatus.RESOLVED
                    || target == DiscrepancyStatus.REJECTED
                    || target == DiscrepancyStatus.ACKNOWLEDGED;
            case RESOLVED, REJECTED -> false;
        };

        if (!valid) {
            throw new InvalidOperationException("Invalid status transition from " + current + " to " + target);
        }
    }

    private void validateAssigneeMembership(UUID organizationId, UUID userId) {
        OrganizationMembership membership = organizationMembershipRepository
                .findByOrganizationIdAndUserId(organizationId, userId)
                .orElseThrow(() -> new IllegalArgumentException("User " + userId + " is not a member of organization " + organizationId));

        if (!membership.isActive()) {
            throw new IllegalArgumentException("User " + userId + " is an inactive member of organization " + organizationId);
        }

        Set<Permission> permissions = RolePermissions.getPermissionsForRole(membership.getRole());
        if (!permissions.contains(Permission.INVENTORY_RECONCILE)) {
            throw new IllegalArgumentException("User " + userId + " does not hold required 'inventory:reconcile' permission");
        }
    }

    private String determineAuditAction(
            DiscrepancyStatus currentStatus,
            DiscrepancyStatus targetStatus,
            UUID currentAssignee,
            UUID newAssignee
    ) {
        if (targetStatus == DiscrepancyStatus.RESOLVED) {
            return AuditAction.DISCREPANCY_RESOLVED;
        }
        if (targetStatus == DiscrepancyStatus.REJECTED) {
            return AuditAction.DISCREPANCY_REJECTED;
        }
        if (targetStatus == DiscrepancyStatus.INVESTIGATING) {
            return AuditAction.DISCREPANCY_ASSIGNED;
        }
        if (targetStatus == DiscrepancyStatus.ACKNOWLEDGED && currentStatus == DiscrepancyStatus.INVESTIGATING) {
            return AuditAction.DISCREPANCY_UNASSIGNED;
        }
        if (currentAssignee != null && newAssignee == null) {
            return AuditAction.DISCREPANCY_UNASSIGNED;
        }
        if (newAssignee != null && !Objects.equals(currentAssignee, newAssignee)) {
            return AuditAction.DISCREPANCY_ASSIGNED;
        }
        return AuditAction.DISCREPANCY_ACKNOWLEDGED;
    }
}
