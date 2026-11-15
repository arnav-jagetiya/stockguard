package com.stockguard.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WarehouseDashboardResponse(
        UUID organizationId,
        UUID warehouseId,
        String warehouseCode,
        String warehouseName,
        boolean active,
        Instant generatedAt,
        WarehouseInventorySummary inventory,
        WarehouseReservationsSummary reservations,
        DashboardSummaryResponse.IntegritySummary integrity,
        List<DashboardSummaryResponse.RecentMovementDto> recentMovements
) {
    public record WarehouseInventorySummary(
            long totalOnHand,
            long totalReserved,
            long totalAvailable,
            long outOfStockProductsCount
    ) {}

    public record WarehouseReservationsSummary(
            long activeAllocationsCount,
            long totalAllocatedUnits
    ) {}
}
