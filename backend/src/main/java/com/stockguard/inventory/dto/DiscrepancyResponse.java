package com.stockguard.inventory.dto;

import com.stockguard.inventory.domain.DiscrepancyStatus;
import com.stockguard.inventory.domain.DiscrepancyType;
import com.stockguard.inventory.domain.ResolutionSource;

import java.time.Instant;
import java.util.UUID;

public record DiscrepancyResponse(
        UUID id,
        UUID organizationId,
        UUID productId,
        UUID warehouseId,
        DiscrepancyType discrepancyType,
        DiscrepancyStatus status,
        long observedBalanceOnHand,
        long observedLedgerOnHand,
        long observedBalanceReserved,
        long observedLedgerReserved,
        int consecutiveDetectionCount,
        Instant firstDetectedAt,
        Instant lastDetectedAt,
        Instant lastVerifiedAt,
        UUID assignedToUserId,
        Boolean assignedToUserActive,
        String investigationNotes,
        ResolutionSource resolutionSource,
        String resolutionReason,
        Instant resolvedAt,
        UUID resolvedByUserId,
        int version
) {}
