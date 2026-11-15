package com.stockguard.dashboard.repository;

import com.stockguard.dashboard.dto.DashboardSummaryResponse;
import com.stockguard.dashboard.dto.WarehouseDashboardResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * High-performance, anti-Cartesian multi-query JDBC operations for operational dashboards per ADR-013.
 */
@Repository
@Profile("!test")
public class JdbcDashboardOperations {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcDashboardOperations(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record WarehouseRecord(UUID id, String code, String name, boolean active) {}

    public record BalanceAggregate(long onHand, long reserved, long available) {}

    public record CatalogCounts(long activeProductsCount, long activeWarehousesCount) {}

    public record ReservationAggregate(long activeCount, long totalUnits) {}

    public Optional<WarehouseRecord> findWarehouse(UUID organizationId, UUID warehouseId) {
        String sql = """
            SELECT id, code, name, active
            FROM warehouses
            WHERE organization_id = :orgId AND id = :warehouseId;
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orgId", organizationId)
                .addValue("warehouseId", warehouseId);

        List<WarehouseRecord> list = jdbcTemplate.query(sql, params, (rs, rowNum) -> new WarehouseRecord(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("name"),
                rs.getBoolean("active")
        ));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public BalanceAggregate getInventoryBalancesTotals(UUID organizationId) {
        String sql = """
            SELECT
                COALESCE(SUM(on_hand), 0) AS total_on_hand,
                COALESCE(SUM(reserved), 0) AS total_reserved,
                COALESCE(SUM(on_hand - reserved), 0) AS total_available
            FROM inventory_balances
            WHERE organization_id = :orgId;
            """;
        MapSqlParameterSource params = new MapSqlParameterSource("orgId", organizationId);
        return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> new BalanceAggregate(
                rs.getLong("total_on_hand"),
                rs.getLong("total_reserved"),
                rs.getLong("total_available")
        ));
    }

    public BalanceAggregate getWarehouseBalancesTotals(UUID organizationId, UUID warehouseId) {
        String sql = """
            SELECT
                COALESCE(SUM(on_hand), 0) AS total_on_hand,
                COALESCE(SUM(reserved), 0) AS total_reserved,
                COALESCE(SUM(on_hand - reserved), 0) AS total_available
            FROM inventory_balances
            WHERE organization_id = :orgId AND warehouse_id = :warehouseId;
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orgId", organizationId)
                .addValue("warehouseId", warehouseId);
        return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> new BalanceAggregate(
                rs.getLong("total_on_hand"),
                rs.getLong("total_reserved"),
                rs.getLong("total_available")
        ));
    }

    public CatalogCounts getCatalogCounts(UUID organizationId) {
        String sql = """
            SELECT
                (SELECT COUNT(*) FROM products WHERE organization_id = :orgId AND active = TRUE) AS active_products_count,
                (SELECT COUNT(*) FROM warehouses WHERE organization_id = :orgId AND active = TRUE) AS active_warehouses_count;
            """;
        MapSqlParameterSource params = new MapSqlParameterSource("orgId", organizationId);
        return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> new CatalogCounts(
                rs.getLong("active_products_count"),
                rs.getLong("active_warehouses_count")
        ));
    }

    public long getOutOfStockActiveProductsCount(UUID organizationId) {
        String sql = """
            SELECT COUNT(*)
            FROM products p
            WHERE p.organization_id = :orgId
              AND p.active = TRUE
              AND NOT EXISTS (
                  SELECT 1
                  FROM inventory_balances b
                  JOIN warehouses w ON b.warehouse_id = w.id AND b.organization_id = w.organization_id
                  WHERE b.organization_id = p.organization_id
                    AND b.product_id = p.id
                    AND w.active = TRUE
                    AND (b.on_hand - b.reserved) > 0
              );
            """;
        MapSqlParameterSource params = new MapSqlParameterSource("orgId", organizationId);
        Long count = jdbcTemplate.queryForObject(sql, params, Long.class);
        return count != null ? count : 0L;
    }

    public long getOutOfStockWarehouseProductsCount(UUID organizationId, UUID warehouseId) {
        String sql = """
            SELECT COUNT(*)
            FROM products p
            WHERE p.organization_id = :orgId
              AND p.active = TRUE
              AND NOT EXISTS (
                  SELECT 1
                  FROM inventory_balances b
                  WHERE b.organization_id = p.organization_id
                    AND b.product_id = p.id
                    AND b.warehouse_id = :warehouseId
                    AND (b.on_hand - b.reserved) > 0
              );
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orgId", organizationId)
                .addValue("warehouseId", warehouseId);
        Long count = jdbcTemplate.queryForObject(sql, params, Long.class);
        return count != null ? count : 0L;
    }

    public ReservationAggregate getReservationMetrics(UUID organizationId) {
        String sql = """
            SELECT
                COUNT(DISTINCT r.id) AS active_reservations_count,
                COALESCE(SUM(a.quantity), 0) AS total_reserved_units
            FROM reservations r
            LEFT JOIN reservation_allocations a
              ON r.id = a.reservation_id AND r.organization_id = a.organization_id
            WHERE r.organization_id = :orgId
              AND r.status = 'ACTIVE';
            """;
        MapSqlParameterSource params = new MapSqlParameterSource("orgId", organizationId);
        return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> new ReservationAggregate(
                rs.getLong("active_reservations_count"),
                rs.getLong("total_reserved_units")
        ));
    }

    public ReservationAggregate getWarehouseAllocationMetrics(UUID organizationId, UUID warehouseId) {
        String sql = """
            SELECT
                COUNT(a.id) AS active_allocations_count,
                COALESCE(SUM(a.quantity), 0) AS total_allocated_units
            FROM reservation_allocations a
            JOIN reservations r ON a.reservation_id = r.id AND a.organization_id = r.organization_id
            WHERE a.organization_id = :orgId
              AND a.warehouse_id = :warehouseId
              AND r.status = 'ACTIVE';
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orgId", organizationId)
                .addValue("warehouseId", warehouseId);
        return jdbcTemplate.queryForObject(sql, params, (rs, rowNum) -> new ReservationAggregate(
                rs.getLong("active_allocations_count"),
                rs.getLong("total_allocated_units")
        ));
    }

    public Map<String, Long> getOrderCountsByStatus(UUID organizationId) {
        String sql = """
            SELECT status, COUNT(*) AS status_count
            FROM orders
            WHERE organization_id = :orgId
            GROUP BY status;
            """;
        MapSqlParameterSource params = new MapSqlParameterSource("orgId", organizationId);
        return jdbcTemplate.query(sql, params, rs -> {
            Map<String, Long> map = new java.util.HashMap<>();
            while (rs.next()) {
                map.put(rs.getString("status"), rs.getLong("status_count"));
            }
            return map;
        });
    }

    public long getOpenDiscrepanciesCount(UUID organizationId) {
        String sql = """
            SELECT COUNT(*)
            FROM reconciliation_discrepancies
            WHERE organization_id = :orgId
              AND status IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING');
            """;
        MapSqlParameterSource params = new MapSqlParameterSource("orgId", organizationId);
        Long count = jdbcTemplate.queryForObject(sql, params, Long.class);
        return count != null ? count : 0L;
    }

    public long getWarehouseOpenDiscrepanciesCount(UUID organizationId, UUID warehouseId) {
        String sql = """
            SELECT COUNT(*)
            FROM reconciliation_discrepancies
            WHERE organization_id = :orgId
              AND warehouse_id = :warehouseId
              AND status IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING');
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orgId", organizationId)
                .addValue("warehouseId", warehouseId);
        Long count = jdbcTemplate.queryForObject(sql, params, Long.class);
        return count != null ? count : 0L;
    }

    public List<DashboardSummaryResponse.RecentMovementDto> getRecentMovements(UUID organizationId, int limit) {
        String sql = """
            SELECT
                t.id,
                t.transaction_type,
                t.reference_type,
                t.reference_id,
                t.created_at,
                COALESCE(u.email, 'SYSTEM') AS created_by_name
            FROM inventory_transactions t
            LEFT JOIN users u ON t.actor_user_id = u.id
            WHERE t.organization_id = :orgId
            ORDER BY t.created_at DESC
            LIMIT :limit;
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orgId", organizationId)
                .addValue("limit", limit);

        return jdbcTemplate.query(sql, params, this::mapMovement);
    }

    public List<DashboardSummaryResponse.RecentMovementDto> getWarehouseRecentMovements(UUID organizationId, UUID warehouseId, int limit) {
        String sql = """
            SELECT DISTINCT
                t.id,
                t.transaction_type,
                t.reference_type,
                t.reference_id,
                t.created_at,
                COALESCE(u.email, 'SYSTEM') AS created_by_name
            FROM inventory_transactions t
            JOIN inventory_ledger_entries l ON t.id = l.transaction_id AND t.organization_id = l.organization_id
            LEFT JOIN users u ON t.actor_user_id = u.id
            WHERE t.organization_id = :orgId
              AND l.warehouse_id = :warehouseId
            ORDER BY t.created_at DESC
            LIMIT :limit;
            """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orgId", organizationId)
                .addValue("warehouseId", warehouseId)
                .addValue("limit", limit);

        return jdbcTemplate.query(sql, params, this::mapMovement);
    }

    private DashboardSummaryResponse.RecentMovementDto mapMovement(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAtTs = rs.getTimestamp("created_at");
        UUID refId = rs.getObject("reference_id", UUID.class);
        return new DashboardSummaryResponse.RecentMovementDto(
                rs.getObject("id", UUID.class),
                rs.getString("transaction_type"),
                rs.getString("reference_type"),
                refId != null ? refId.toString() : null,
                createdAtTs != null ? createdAtTs.toInstant() : null,
                rs.getString("created_by_name")
        );
    }
}
