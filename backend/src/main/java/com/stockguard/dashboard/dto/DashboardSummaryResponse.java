package com.stockguard.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record DashboardSummaryResponse(
        UUID organizationId,
        Instant generatedAt,
        CatalogSummary catalog,
        InventorySummary inventory,
        ReservationsSummary reservations,
        OrderPipelineSummary orders,
        IntegritySummary integrity,
        List<RecentMovementDto> recentMovements
) {
    public record CatalogSummary(long activeProductsCount, long activeWarehousesCount) {}

    public record InventorySummary(long totalOnHand, long totalReserved, long totalAvailable, long outOfStockActiveProductsCount) {}

    public record ReservationsSummary(long activeReservationsCount, long totalReservedUnits) {}

    public record OrderPipelineSummary(long pendingCount, long confirmedCount, long fulfilledCount, long cancelledCount) {}

    public record IntegritySummary(long openDiscrepanciesCount) {}

    public record RecentMovementDto(
            UUID transactionId,
            String transactionType,
            String referenceType,
            String referenceId,
            Instant createdAt,
            String createdByName
    ) {}
}
