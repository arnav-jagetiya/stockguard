package com.stockguard.inventory.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReconciliationReport(
        UUID organizationId,
        int totalBalancesInspected,
        int matchingBalances,
        int discrepantBalances,
        List<ReconciliationDiscrepancy> discrepancies,
        Instant reconciledAt
) {}
