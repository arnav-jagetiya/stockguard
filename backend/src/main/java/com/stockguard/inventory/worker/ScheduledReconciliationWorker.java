package com.stockguard.inventory.worker;

import com.stockguard.inventory.config.ReconciliationProperties;
import com.stockguard.inventory.dto.DiscrepancySyncResult;
import com.stockguard.inventory.dto.ReconciliationReport;
import com.stockguard.inventory.dto.ReconciliationSweepResult;
import com.stockguard.inventory.service.DiscrepancyManagementService;
import com.stockguard.inventory.service.InventoryReconciliationService;
import com.stockguard.shared.lock.PostgresAdvisoryLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduled background worker executing multi-tenant inventory reconciliation sweeps per ADR-012.
 * <p>
 * Operational Architecture:
 * <ul>
 *   <li>Coordinated across multiple cluster instances via dedicated-connection session advisory lock {@code 0x53544B4752445243L}.</li>
 *   <li>Coordinator method is strictly non-transactional.</li>
 *   <li>Processes each organization in isolated transaction sequence: Tx1 (read-only) then Tx2 (atomic sync & audit).</li>
 *   <li>Failure in one tenant does not affect subsequent tenants (fault-isolated with bounded 1-retry policy).</li>
 *   <li>Responds to graceful shutdown events by stopping iteration cleanly.</li>
 * </ul>
 */
@Component
@Profile("!test")
public class ScheduledReconciliationWorker implements ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(ScheduledReconciliationWorker.class);

    /**
     * 64-bit constant session advisory lock key: 0x53544B4752445243L ("STKGRDRC").
     */
    public static final long RECONCILIATION_LOCK_KEY = 0x53544B4752445243L;

    private final PostgresAdvisoryLockService advisoryLockService;
    private final InventoryReconciliationService reconciliationService;
    private final DiscrepancyManagementService discrepancyManagementService;
    private final ReconciliationProperties properties;
    private final JdbcTemplate jdbcTemplate;

    private final AtomicBoolean isShuttingDown = new AtomicBoolean(false);

    public ScheduledReconciliationWorker(
            PostgresAdvisoryLockService advisoryLockService,
            InventoryReconciliationService reconciliationService,
            DiscrepancyManagementService discrepancyManagementService,
            ReconciliationProperties properties,
            JdbcTemplate jdbcTemplate
    ) {
        this.advisoryLockService = Objects.requireNonNull(advisoryLockService, "PostgresAdvisoryLockService must not be null");
        this.reconciliationService = Objects.requireNonNull(reconciliationService, "InventoryReconciliationService must not be null");
        this.discrepancyManagementService = Objects.requireNonNull(discrepancyManagementService, "DiscrepancyManagementService must not be null");
        this.properties = Objects.requireNonNull(properties, "ReconciliationProperties must not be null");
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "JdbcTemplate must not be null");
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        log.info("Application context is closing; requesting graceful stop for ScheduledReconciliationWorker.");
        isShuttingDown.set(true);
    }

    /**
     * Scheduled entrypoint configured via cron expression in UTC.
     */
    @Scheduled(cron = "${stockguard.reconciliation.scheduled.cron:0 0 2 * * *}", zone = "UTC")
    public void scheduledSweep() {
        if (!properties.getScheduled().isEnabled()) {
            log.debug("Scheduled inventory reconciliation is disabled by configuration.");
            return;
        }
        runSweep();
    }

    /**
     * Executes a complete multi-tenant reconciliation sweep under session advisory lock coordination.
     * Can be invoked programmatically or by the scheduled trigger.
     *
     * @return summary of the sweep outcome
     */
    public ReconciliationSweepResult runSweep() {
        if (isShuttingDown.get()) {
            log.warn("Reconciliation sweep skipped because application is shutting down.");
            return new ReconciliationSweepResult(0, 0, 0, false, false);
        }

        log.info("Attempting to acquire session advisory lock [{}] for scheduled reconciliation sweep...", RECONCILIATION_LOCK_KEY);

        Optional<ReconciliationSweepResult> result = advisoryLockService.executeWithLock(
                RECONCILIATION_LOCK_KEY,
                this::executeReconciliationLoop
        );

        if (result.isEmpty()) {
            log.info("Reconciliation sweep skipped: advisory lock [{}] is held by another cluster instance.", RECONCILIATION_LOCK_KEY);
            return new ReconciliationSweepResult(0, 0, 0, false, false);
        }

        ReconciliationSweepResult sweepResult = result.get();
        log.info("Reconciliation sweep finished: attempted={}, succeeded={}, failed={}, completedFully={}",
                sweepResult.organizationsAttempted(),
                sweepResult.organizationsSucceeded(),
                sweepResult.organizationsFailed(),
                sweepResult.completedFully());

        return sweepResult;
    }

    /**
     * Internal coordinated loop over active organizations executed while holding the advisory lock.
     * Processes organizations using bounded keyset pagination to avoid loading all tenant IDs into memory.
     */
    private ReconciliationSweepResult executeReconciliationLoop() {
        long startTime = System.currentTimeMillis();
        long maxDurationMs = properties.getScheduled().getMaxDurationSeconds() * 1000L;
        int batchSize = properties.getScheduled().getTenantBatchSize();

        log.info("Starting scheduled reconciliation sweep with tenantBatchSize={}...", batchSize);

        int attempted = 0;
        int succeeded = 0;
        int failed = 0;
        boolean completedFully = true;
        UUID lastSeenId = null;

        while (true) {
            if (isShuttingDown.get()) {
                log.info("Graceful shutdown detected between tenant batches. Terminating sweep cleanly.");
                completedFully = false;
                break;
            }

            if (System.currentTimeMillis() - startTime > maxDurationMs) {
                log.warn("Reconciliation sweep exceeded maximum duration of {}s. Terminating sweep early.",
                        properties.getScheduled().getMaxDurationSeconds());
                completedFully = false;
                break;
            }

            List<UUID> batch = fetchNextOrganizationBatch(lastSeenId, batchSize);
            if (batch == null || batch.isEmpty()) {
                break;
            }

            for (UUID organizationId : batch) {
                lastSeenId = organizationId;

                if (isShuttingDown.get()) {
                    log.info("Graceful shutdown detected during sweep. Terminating loop cleanly.");
                    completedFully = false;
                    break;
                }

                if (System.currentTimeMillis() - startTime > maxDurationMs) {
                    log.warn("Reconciliation sweep exceeded maximum duration of {}s. Terminating sweep early.",
                            properties.getScheduled().getMaxDurationSeconds());
                    completedFully = false;
                    break;
                }

                attempted++;
                boolean tenantSuccess = processOrganizationWithRetry(organizationId);
                if (tenantSuccess) {
                    succeeded++;
                } else {
                    failed++;
                }
            }

            if (!completedFully) {
                break;
            }
        }

        return new ReconciliationSweepResult(attempted, succeeded, failed, true, completedFully);
    }

    /**
     * Reconciles a single organization with bounded retry policy (at most 1 retry).
     */
    private boolean processOrganizationWithRetry(UUID organizationId) {
        int maxAttempts = 2; // Initial attempt + 1 retry per ADR-012
        int attempt = 0;

        while (attempt < maxAttempts) {
            attempt++;
            try {
                // Step 1: Tx1 (Read-Only Reconciliation under REPEATABLE_READ)
                ReconciliationReport report = reconciliationService.reconcile(organizationId);
                if (report == null) {
                    throw new IllegalStateException("Reconciliation report was null for org: " + organizationId);
                }

                // Step 2: Tx2 (Atomic Discrepancy & Audit Persistence under REQUIRED)
                DiscrepancySyncResult syncResult = discrepancyManagementService.syncDiscrepancies(organizationId, report);

                log.info("Successfully reconciled organization [{}] (attempt {}/{}): inspected={}, discrepancies={}, autoResolved={}",
                        organizationId, attempt, maxAttempts,
                        report.totalBalancesInspected(),
                        syncResult != null ? syncResult.newDiscrepancies() : 0,
                        syncResult != null ? syncResult.autoResolved() : 0);
                return true;

            } catch (Exception ex) {
                if (attempt < maxAttempts && !isShuttingDown.get()) {
                    log.warn("Reconciliation failed for organization [{}] on attempt 1/{}. Retrying once. Error: {}",
                            organizationId, maxAttempts, ex.getMessage());
                } else {
                    log.error("Reconciliation failed permanently for organization [{}] after {} attempt(s): {}",
                            organizationId, attempt, ex.getMessage(), ex);
                    return false;
                }
            }
        }

        return false;
    }

    /**
     * Retrieves the next batch of tenant organization IDs using keyset pagination.
     * Guarantees deterministic ascending UUID ordering and bounded memory footprint.
     *
     * @param lastSeenId last processed organization UUID, or null for initial page
     * @param batchSize maximum number of IDs to fetch
     * @return batch of organization IDs
     */
    public List<UUID> fetchNextOrganizationBatch(UUID lastSeenId, int batchSize) {
        if (lastSeenId == null) {
            String sql = "SELECT id FROM organizations ORDER BY id ASC LIMIT ?";
            return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getObject("id", UUID.class), batchSize);
        } else {
            String sql = "SELECT id FROM organizations WHERE id > ? ORDER BY id ASC LIMIT ?";
            return jdbcTemplate.query(sql, (rs, rowNum) -> rs.getObject("id", UUID.class), lastSeenId, batchSize);
        }
    }

    /**
     * Retrieves all active tenant organization IDs up to the configured batch size.
     */
    public List<UUID> fetchActiveOrganizationIds() {
        return fetchNextOrganizationBatch(null, properties.getScheduled().getTenantBatchSize());
    }
}
