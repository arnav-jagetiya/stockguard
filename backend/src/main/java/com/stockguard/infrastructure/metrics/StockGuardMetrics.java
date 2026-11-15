package com.stockguard.infrastructure.metrics;

import com.stockguard.inventory.domain.DiscrepancyStatus;
import com.stockguard.inventory.domain.DiscrepancyType;
import com.stockguard.inventory.repository.JdbcDiscrepancyOperations;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Centralized low-cardinality metrics instrumentation component for StockGuard per ADR-014.
 * <p>
 * Strictly enforces compile-time tags and finite value sets to prevent cardinality explosions
 * in Prometheus TSDB storage. Never accepts or records UUIDs, trace IDs, emails, or free-form strings.
 */
@Component
public class StockGuardMetrics {

    private static final Logger log = LoggerFactory.getLogger(StockGuardMetrics.class);

    private final MeterRegistry registry;

    // Reconciliation meters
    private final Counter reconciliationExecutionsSuccess;
    private final Counter reconciliationExecutionsFailure;
    private final Timer reconciliationDurationSuccess;
    private final Timer reconciliationDurationFailure;
    private final Counter reconciliationOrgExecutionsSuccess;
    private final Counter reconciliationOrgExecutionsFailure;
    private final Counter reconciliationOrgFailuresTotal;
    private final Counter reconciliationLockSkippedTotal;

    // Discrepancy detection counters by type
    private final Map<String, Counter> discrepanciesDetectedByType = new ConcurrentHashMap<>();

    // Reservation counters
    private final Counter reservationsCreatedTotal;
    private final Counter reservationsExpiredSuccess;
    private final Counter reservationsExpiredFailed;
    private final Counter reservationsReleasedTotal;
    private final Counter reservationsConsumedTotal;

    // Auth failures by reason
    private final Map<String, Counter> authFailuresByReason = new ConcurrentHashMap<>();

    // Deadlock retries
    private final Counter deadlockRetriesTotal;

    // Inventory operations cache
    private final Map<String, Counter> inventoryOperations = new ConcurrentHashMap<>();

    public StockGuardMetrics(
            MeterRegistry registry,
            ObjectProvider<JdbcDiscrepancyOperations> discrepancyOperationsProvider
    ) {
        this.registry = Objects.requireNonNull(registry, "MeterRegistry must not be null");

        // 1. Reconciliation sweep executions
        this.reconciliationExecutionsSuccess = Counter.builder("stockguard.reconciliation.executions.total")
                .description("Total reconciliation sweeps executed successfully")
                .tag("status", "SUCCESS")
                .register(registry);
        this.reconciliationExecutionsFailure = Counter.builder("stockguard.reconciliation.executions.total")
                .description("Total reconciliation sweeps failed")
                .tag("status", "FAILURE")
                .register(registry);

        // 2. Reconciliation duration timers
        this.reconciliationDurationSuccess = Timer.builder("stockguard.reconciliation.duration.seconds")
                .description("Execution duration distribution of successful reconciliation sweeps")
                .tag("status", "SUCCESS")
                .register(registry);
        this.reconciliationDurationFailure = Timer.builder("stockguard.reconciliation.duration.seconds")
                .description("Execution duration distribution of failed reconciliation sweeps")
                .tag("status", "FAILURE")
                .register(registry);

        // 3. Organization executions
        this.reconciliationOrgExecutionsSuccess = Counter.builder("stockguard.reconciliation.org.executions.total")
                .description("Per-organization reconciliation execution count - success")
                .tag("status", "SUCCESS")
                .register(registry);
        this.reconciliationOrgExecutionsFailure = Counter.builder("stockguard.reconciliation.org.executions.total")
                .description("Per-organization reconciliation execution count - failure")
                .tag("status", "FAILURE")
                .register(registry);

        // 4. Organization failures
        this.reconciliationOrgFailuresTotal = Counter.builder("stockguard.reconciliation.org.failures.total")
                .description("Total organization-level reconciliation failures")
                .register(registry);

        // 5. Lock skipped
        this.reconciliationLockSkippedTotal = Counter.builder("stockguard.reconciliation.lock.skipped.total")
                .description("Total sweeps skipped due to advisory lock contention")
                .register(registry);

        // 6. Pre-register discrepancy detected counters
        for (DiscrepancyType type : DiscrepancyType.values()) {
            this.discrepanciesDetectedByType.put(type.name(), Counter.builder("stockguard.reconciliation.discrepancies.detected")
                    .description("Total discrepancies identified during reconciliation sweeps")
                    .tag("discrepancy_type", type.name())
                    .register(registry));
        }

        // 7. Register active discrepancy gauges for active statuses
        registerActiveDiscrepancyGauge(DiscrepancyStatus.OPEN, discrepancyOperationsProvider);
        registerActiveDiscrepancyGauge(DiscrepancyStatus.ACKNOWLEDGED, discrepancyOperationsProvider);
        registerActiveDiscrepancyGauge(DiscrepancyStatus.INVESTIGATING, discrepancyOperationsProvider);

        // 8. Reservations
        this.reservationsCreatedTotal = Counter.builder("stockguard.reservations.created.total")
                .description("Total stock reservations successfully created")
                .register(registry);
        this.reservationsExpiredSuccess = Counter.builder("stockguard.reservations.expired.total")
                .description("Total reservations cancelled by expiration worker - success")
                .tag("status", "SUCCESS")
                .register(registry);
        this.reservationsExpiredFailed = Counter.builder("stockguard.reservations.expired.total")
                .description("Total reservations cancelled by expiration worker - failed")
                .tag("status", "FAILED")
                .register(registry);
        this.reservationsReleasedTotal = Counter.builder("stockguard.reservations.released.total")
                .description("Total reservations explicitly released by operators")
                .register(registry);
        this.reservationsConsumedTotal = Counter.builder("stockguard.reservations.consumed.total")
                .description("Total reservations converted into fulfilled orders")
                .register(registry);

        // 9. Auth failures
        for (String reason : new String[]{"bad_credentials", "token_expired", "replay_detected"}) {
            this.authFailuresByReason.put(reason, Counter.builder("stockguard.auth.failures.total")
                    .description("Total unauthenticated/failed authentication attempts")
                    .tag("reason", reason)
                    .register(registry));
        }

        // 10. Database deadlock retries
        this.deadlockRetriesTotal = Counter.builder("stockguard.db.deadlock.retries.total")
                .description("Total database transaction retries triggered by PostgreSQL deadlocks or serialization failures")
                .register(registry);
    }

    private void registerActiveDiscrepancyGauge(
            DiscrepancyStatus status,
            ObjectProvider<JdbcDiscrepancyOperations> discrepancyOperationsProvider
    ) {
        Gauge.builder("stockguard.reconciliation.discrepancies.active", () -> {
            try {
                JdbcDiscrepancyOperations ops = discrepancyOperationsProvider.getIfAvailable();
                return ops != null ? ops.countActiveByStatus(status) : 0L;
            } catch (Exception e) {
                log.debug("Unable to poll discrepancy active count gauge for status {}: {}", status, e.getMessage());
                return 0L;
            }
        })
        .description("Current count of unresolved discrepancies across all tenants")
        .tag("status", status.name())
        .register(registry);
    }

    // -------------------------------------------------------------------------
    // Record Methods
    // -------------------------------------------------------------------------

    public void recordReconciliationSweep(boolean success, long durationMillis) {
        if (success) {
            reconciliationExecutionsSuccess.increment();
            reconciliationDurationSuccess.record(durationMillis, TimeUnit.MILLISECONDS);
        } else {
            reconciliationExecutionsFailure.increment();
            reconciliationDurationFailure.record(durationMillis, TimeUnit.MILLISECONDS);
        }
    }

    public void recordReconciliationOrgExecution(boolean success) {
        if (success) {
            reconciliationOrgExecutionsSuccess.increment();
        } else {
            reconciliationOrgExecutionsFailure.increment();
            reconciliationOrgFailuresTotal.increment();
        }
    }

    public void recordReconciliationLockSkipped() {
        reconciliationLockSkippedTotal.increment();
    }

    public void recordDiscrepancyDetected(DiscrepancyType type) {
        if (type == null) {
            return;
        }
        Counter counter = discrepanciesDetectedByType.computeIfAbsent(type.name(), k ->
                Counter.builder("stockguard.reconciliation.discrepancies.detected")
                        .description("Total discrepancies identified during reconciliation sweeps")
                        .tag("discrepancy_type", k)
                        .register(registry)
        );
        counter.increment();
    }

    public void recordReservationCreated() {
        reservationsCreatedTotal.increment();
    }

    public void recordReservationsExpired(int successCount, int failureCount) {
        if (successCount > 0) {
            reservationsExpiredSuccess.increment(successCount);
        }
        if (failureCount > 0) {
            reservationsExpiredFailed.increment(failureCount);
        }
    }

    public void recordReservationReleased() {
        reservationsReleasedTotal.increment();
    }

    public void recordReservationConsumed() {
        reservationsConsumedTotal.increment();
    }

    public void recordInventoryOperation(String operationType, boolean success) {
        String status = success ? "SUCCESS" : "FAILURE";
        String key = operationType + ":" + status;
        Counter counter = inventoryOperations.computeIfAbsent(key, k ->
                Counter.builder("stockguard.inventory.operations.total")
                        .description("Total inventory transactions processed")
                        .tag("operation_type", operationType)
                        .tag("status", status)
                        .register(registry)
        );
        counter.increment();
    }

    public void recordAuthFailure(String reason) {
        Counter counter = authFailuresByReason.computeIfAbsent(reason, k ->
                Counter.builder("stockguard.auth.failures.total")
                        .description("Total unauthenticated/failed authentication attempts")
                        .tag("reason", k)
                        .register(registry)
        );
        counter.increment();
    }

    public void recordDeadlockRetry() {
        deadlockRetriesTotal.increment();
    }

    public MeterRegistry getRegistry() {
        return registry;
    }
}
