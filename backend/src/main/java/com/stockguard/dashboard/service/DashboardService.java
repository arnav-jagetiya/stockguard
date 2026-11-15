package com.stockguard.dashboard.service;

import com.stockguard.dashboard.dto.DashboardSummaryResponse;
import com.stockguard.dashboard.dto.WarehouseDashboardResponse;
import com.stockguard.dashboard.repository.JdbcDashboardOperations;
import com.stockguard.shared.exception.ResourceNotFoundException;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Service orchestrating anti-Cartesian multi-query aggregations for operational dashboards per ADR-013.
 * Executes within a strict read-only snapshot transaction under {@code Isolation.REPEATABLE_READ}.
 */
@Service
@Profile("!test")
public class DashboardService {

    private final JdbcDashboardOperations jdbcDashboardOperations;

    public DashboardService(JdbcDashboardOperations jdbcDashboardOperations) {
        this.jdbcDashboardOperations = Objects.requireNonNull(jdbcDashboardOperations, "JdbcDashboardOperations must not be null");
    }

    /**
     * Aggregates organization-wide operational metrics from an identical MVCC snapshot point-in-time.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DashboardSummaryResponse getDashboardSummary(UUID organizationId) {
        Objects.requireNonNull(organizationId, "Organization ID must not be null");
        Instant generatedAt = Instant.now();

        // Q1: Inventory Balances Totals
        JdbcDashboardOperations.BalanceAggregate balanceAggregate = jdbcDashboardOperations.getInventoryBalancesTotals(organizationId);

        // Q2: Active Catalog Counts
        JdbcDashboardOperations.CatalogCounts catalogCounts = jdbcDashboardOperations.getCatalogCounts(organizationId);

        // Q3: Out-of-Stock Active Products Count
        long outOfStockCount = jdbcDashboardOperations.getOutOfStockActiveProductsCount(organizationId);

        // Q4: Active Reservations & Units
        JdbcDashboardOperations.ReservationAggregate reservationAggregate = jdbcDashboardOperations.getReservationMetrics(organizationId);

        // Q5: Order Pipeline Breakdown
        Map<String, Long> orderCounts = jdbcDashboardOperations.getOrderCountsByStatus(organizationId);
        long pendingOrders = orderCounts.getOrDefault("PENDING", 0L);
        long confirmedOrders = orderCounts.getOrDefault("CONFIRMED", 0L);
        long fulfilledOrders = orderCounts.getOrDefault("FULFILLED", 0L);
        long cancelledOrders = orderCounts.getOrDefault("CANCELLED", 0L);

        // Q6: Open Discrepancies Count
        long openDiscrepancies = jdbcDashboardOperations.getOpenDiscrepanciesCount(organizationId);

        // Q7: Recent Movements Feed (limit 10)
        List<DashboardSummaryResponse.RecentMovementDto> recentMovements = jdbcDashboardOperations.getRecentMovements(organizationId, 10);

        return new DashboardSummaryResponse(
                organizationId,
                generatedAt,
                new DashboardSummaryResponse.CatalogSummary(
                        catalogCounts.activeProductsCount(),
                        catalogCounts.activeWarehousesCount()
                ),
                new DashboardSummaryResponse.InventorySummary(
                        balanceAggregate.onHand(),
                        balanceAggregate.reserved(),
                        balanceAggregate.available(),
                        outOfStockCount
                ),
                new DashboardSummaryResponse.ReservationsSummary(
                        reservationAggregate.activeCount(),
                        reservationAggregate.totalUnits()
                ),
                new DashboardSummaryResponse.OrderPipelineSummary(
                        pendingOrders,
                        confirmedOrders,
                        fulfilledOrders,
                        cancelledOrders
                ),
                new DashboardSummaryResponse.IntegritySummary(openDiscrepancies),
                recentMovements
        );
    }

    /**
     * Aggregates warehouse-specific drilldown metrics from an identical MVCC snapshot point-in-time.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public WarehouseDashboardResponse getWarehouseDashboard(UUID organizationId, UUID warehouseId) {
        Objects.requireNonNull(organizationId, "Organization ID must not be null");
        Objects.requireNonNull(warehouseId, "Warehouse ID must not be null");
        Instant generatedAt = Instant.now();

        // 1. Verify warehouse exists and belongs to the organization
        JdbcDashboardOperations.WarehouseRecord warehouse = jdbcDashboardOperations.findWarehouse(organizationId, warehouseId)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + warehouseId));

        // 2. Warehouse Inventory Balances
        JdbcDashboardOperations.BalanceAggregate balanceAggregate = jdbcDashboardOperations.getWarehouseBalancesTotals(organizationId, warehouseId);

        // 3. Out-of-Stock for Warehouse
        long outOfStockCount = jdbcDashboardOperations.getOutOfStockWarehouseProductsCount(organizationId, warehouseId);

        // 4. Warehouse Active Allocations
        JdbcDashboardOperations.ReservationAggregate allocationAggregate = jdbcDashboardOperations.getWarehouseAllocationMetrics(organizationId, warehouseId);

        // 5. Open Discrepancies for Warehouse
        long openDiscrepancies = jdbcDashboardOperations.getWarehouseOpenDiscrepanciesCount(organizationId, warehouseId);

        // 6. Recent Movements affecting Warehouse
        List<DashboardSummaryResponse.RecentMovementDto> recentMovements = jdbcDashboardOperations.getWarehouseRecentMovements(organizationId, warehouseId, 10);

        return new WarehouseDashboardResponse(
                organizationId,
                warehouse.id(),
                warehouse.code(),
                warehouse.name(),
                warehouse.active(),
                generatedAt,
                new WarehouseDashboardResponse.WarehouseInventorySummary(
                        balanceAggregate.onHand(),
                        balanceAggregate.reserved(),
                        balanceAggregate.available(),
                        outOfStockCount
                ),
                new WarehouseDashboardResponse.WarehouseReservationsSummary(
                        allocationAggregate.activeCount(),
                        allocationAggregate.totalUnits()
                ),
                new DashboardSummaryResponse.IntegritySummary(openDiscrepancies),
                recentMovements
        );
    }
}
