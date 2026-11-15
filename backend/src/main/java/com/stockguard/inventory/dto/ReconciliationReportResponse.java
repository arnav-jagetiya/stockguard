package com.stockguard.inventory.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReconciliationReportResponse(
        UUID organizationId,
        long totalBalancesInspected,
        long matchingBalances,
        long discrepantBalances,
        Instant reconciledAt,
        List<ReconciliationDiscrepancy> discrepancies
) {
    public static ReconciliationReportResponse from(ReconciliationReport report) {
        return new ReconciliationReportResponse(
                report.organizationId(),
                report.totalBalancesInspected(),
                report.matchingBalances(),
                report.discrepantBalances(),
                report.reconciledAt(),
                report.discrepancies()
        );
    }
}
