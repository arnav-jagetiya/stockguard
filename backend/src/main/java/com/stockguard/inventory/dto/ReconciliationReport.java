package com.stockguard.inventory.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record ReconciliationReport(
        UUID organizationId,
        int totalBalancesInspected,
        int matchingBalances,
        int discrepantBalances,
        List<ReconciliationDiscrepancy> discrepancies,
        Instant reconciledAt,
        Map<String, SnapshotActiveDiscrepancy> snapshotActiveDiscrepanciesByPosition,
        Map<UUID, SnapshotActiveDiscrepancy> snapshotActiveDiscrepanciesById,
        Set<UUID> snapshotActiveDiscrepancyIds
) {
    public record SnapshotActiveDiscrepancy(
            UUID id,
            UUID productId,
            UUID warehouseId,
            String status,
            int version,
            Instant lastDetectedAt
    ) {}

    public ReconciliationReport(
            UUID organizationId,
            int totalBalancesInspected,
            int matchingBalances,
            int discrepantBalances,
            List<ReconciliationDiscrepancy> discrepancies,
            Instant reconciledAt,
            Map<String, SnapshotActiveDiscrepancy> snapshotActiveDiscrepanciesByPosition,
            Set<UUID> snapshotActiveDiscrepancyIds
    ) {
        this(organizationId, totalBalancesInspected, matchingBalances, discrepantBalances, discrepancies, reconciledAt,
                snapshotActiveDiscrepanciesByPosition, null, snapshotActiveDiscrepancyIds);
    }

    public ReconciliationReport(
            UUID organizationId,
            int totalBalancesInspected,
            int matchingBalances,
            int discrepantBalances,
            List<ReconciliationDiscrepancy> discrepancies,
            Instant reconciledAt,
            Set<UUID> snapshotActiveDiscrepancyIds
    ) {
        this(organizationId, totalBalancesInspected, matchingBalances, discrepantBalances, discrepancies, reconciledAt, null, null, snapshotActiveDiscrepancyIds);
    }

    public ReconciliationReport(
            UUID organizationId,
            int totalBalancesInspected,
            int matchingBalances,
            int discrepantBalances,
            List<ReconciliationDiscrepancy> discrepancies,
            Instant reconciledAt
    ) {
        this(organizationId, totalBalancesInspected, matchingBalances, discrepantBalances, discrepancies, reconciledAt, null, null, null);
    }
}
