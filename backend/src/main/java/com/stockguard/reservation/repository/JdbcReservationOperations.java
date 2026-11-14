package com.stockguard.reservation.repository;

import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.order.domain.OrderStatus;
import com.stockguard.reservation.domain.ReservationStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * Spring JDBC operations for the reservation engine:
 * <ul>
 *   <li>Enforces the strict canonical lock hierarchy across orders, reservations, warehouses, products, and balances.</li>
 *   <li>Never acquires balance locks in warehouse priority order.</li>
 *   <li>Never auto-creates blank inventory balance rows for unstocked products.</li>
 *   <li>Performs atomic batch inserts and signed delta balance updates.</li>
 * </ul>
 */
@Repository
public class JdbcReservationOperations {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public JdbcReservationOperations(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplate = jdbcTemplateProvider != null ? jdbcTemplateProvider.getIfAvailable() : null;
    }

    public JdbcReservationOperations(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record LockedOrderInfo(UUID id, UUID organizationId, OrderStatus status) {}

    public record LockedReservationInfo(UUID id, String status) {}

    public record LockedWarehouseInfo(UUID id, String code, String normalizedCode, String name, int priority, boolean active) {}

    public record LockedProductInfo(UUID id, String sku, String name, boolean active) {}

    /**
     * 1. Lock the order row FOR UPDATE.
     */
    public Optional<LockedOrderInfo> lockOrder(UUID organizationId, UUID orderId) {
        String sql = "SELECT id, organization_id, status FROM orders WHERE organization_id = ? AND id = ? FOR UPDATE";
        try {
            LockedOrderInfo order = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new LockedOrderInfo(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("organization_id")),
                    OrderStatus.valueOf(rs.getString("status"))
            ), organizationId, orderId);
            return Optional.ofNullable(order);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 2. Lock any existing reservation for the order FOR UPDATE.
     */
    public Optional<LockedReservationInfo> lockReservationForOrder(UUID organizationId, UUID orderId) {
        String sql = "SELECT id, status FROM reservations WHERE organization_id = ? AND order_id = ? FOR UPDATE";
        try {
            LockedReservationInfo res = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new LockedReservationInfo(
                    UUID.fromString(rs.getString("id")),
                    rs.getString("status")
            ), organizationId, orderId);
            return Optional.ofNullable(res);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 3. Lock warehouse rows using FOR SHARE, sorted strictly by warehouse ID ascending (ADR-003).
     */
    public List<LockedWarehouseInfo> lockAndGetWarehouses(UUID organizationId) {
        String sql = """
                SELECT id, code, normalized_code, name, priority, active
                FROM warehouses
                WHERE organization_id = ?
                ORDER BY id ASC
                FOR SHARE
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new LockedWarehouseInfo(
                UUID.fromString(rs.getString("id")),
                rs.getString("code"),
                rs.getString("normalized_code"),
                rs.getString("name"),
                rs.getInt("priority"),
                rs.getBoolean("active")
        ), organizationId);
    }

    /**
     * 4. Lock product rows using FOR SHARE, sorted strictly by product ID ascending.
     */
    public List<LockedProductInfo> lockAndGetProducts(UUID organizationId, List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<UUID> sorted = new ArrayList<>(new HashSet<>(productIds));
        Collections.sort(sorted);
        String inSql = String.join(",", Collections.nCopies(sorted.size(), "?"));
        String sql = "SELECT id, sku, name, active FROM products WHERE organization_id = ? AND id IN (" + inSql + ") ORDER BY id ASC FOR SHARE";

        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        params.addAll(sorted);

        return jdbcTemplate.query(sql, (rs, rowNum) -> new LockedProductInfo(
                UUID.fromString(rs.getString("id")),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getBoolean("active")
        ), params.toArray());
    }

    /**
     * 5. Lock inventory balance rows using FOR UPDATE, sorted canonically by product_id ASC, warehouse_id ASC.
     * <p>
     * NOTE: Missing balance rows are NOT created. Missing rows represent zero available stock.
     */
    public List<InventoryBalance> lockAndGetExistingBalances(UUID organizationId, List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<UUID> sortedProducts = new ArrayList<>(new HashSet<>(productIds));
        Collections.sort(sortedProducts);
        String inSql = String.join(",", Collections.nCopies(sortedProducts.size(), "?"));

        String sql = """
                SELECT id, organization_id, product_id, warehouse_id, on_hand, reserved, updated_at
                FROM inventory_balances
                WHERE organization_id = ? AND product_id IN (
                """ + inSql + """
                )
                ORDER BY product_id ASC, warehouse_id ASC
                FOR UPDATE
                """;

        List<Object> params = new ArrayList<>();
        params.add(organizationId);
        params.addAll(sortedProducts);

        return jdbcTemplate.query(sql, new InventoryBalanceRowMapper(), params.toArray());
    }

    public record InsertedReservationHeader(
            UUID id,
            UUID organizationId,
            UUID orderId,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt
    ) {}

    /**
     * Inserts the reservation header with PostgreSQL database-authoritative expiry.
     * <p>
     * PostgreSQL calculates expires_at using its authoritative database clock via:
     * {@code NOW() + (? * INTERVAL '1 minute')}.
     * The configured TTL is bound as a parameter.
     */
    public InsertedReservationHeader insertReservation(UUID id, UUID organizationId, UUID orderId, long ttlMinutes) {
        String sql = """
                INSERT INTO reservations (
                    id, organization_id, order_id, status, expires_at, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, 'ACTIVE', NOW() + (? * INTERVAL '1 minute'), NOW(), NOW()
                )
                RETURNING id, organization_id, order_id, expires_at, created_at, updated_at
                """;
        return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> new InsertedReservationHeader(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("organization_id")),
                UUID.fromString(rs.getString("order_id")),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        ), id, organizationId, orderId, ttlMinutes);
    }

    /**
     * Inserts a reservation allocation row.
     */
    public void insertReservationAllocation(
            UUID id,
            UUID organizationId,
            UUID reservationId,
            UUID orderId,
            UUID orderItemId,
            UUID productId,
            UUID warehouseId,
            long quantity
    ) {
        String sql = """
                INSERT INTO reservation_allocations (
                    id, organization_id, reservation_id, order_id, order_item_id,
                    product_id, warehouse_id, quantity, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, NOW()
                )
                """;
        jdbcTemplate.update(sql, id, organizationId, reservationId, orderId, orderItemId, productId, warehouseId, quantity);
    }

    /**
     * Inserts an inventory transaction header.
     */
    public void insertInventoryTransaction(
            UUID id,
            UUID organizationId,
            String transactionType,
            String referenceType,
            UUID referenceId,
            String notes,
            UUID actorUserId
    ) {
        String sql = """
                INSERT INTO inventory_transactions (
                    id, organization_id, transaction_type, reference_type, reference_id, notes, actor_user_id, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, NOW()
                )
                """;
        jdbcTemplate.update(sql, id, organizationId, transactionType, referenceType, referenceId, notes, actorUserId);
    }

    /**
     * Inserts an inventory ledger entry.
     */
    public void insertInventoryLedgerEntry(
            UUID id,
            UUID organizationId,
            UUID transactionId,
            int entryNumber,
            UUID productId,
            UUID warehouseId,
            long onHandDelta,
            long reservedDelta
    ) {
        String sql = """
                INSERT INTO inventory_ledger_entries (
                    id, organization_id, transaction_id, entry_number, product_id, warehouse_id,
                    on_hand_delta, reserved_delta, transfer_item_id, transfer_direction, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?,
                    ?, ?, NULL, NULL, NOW()
                )
                """;
        jdbcTemplate.update(sql, id, organizationId, transactionId, entryNumber, productId, warehouseId, onHandDelta, reservedDelta);
    }

    public record LockedAllocationInfo(UUID id, UUID orderItemId, UUID productId, UUID warehouseId, long quantity) {}

    /**
     * Clears reservation expires_at (making the reservation non-expiring upon order confirmation).
     */
    public void clearReservationExpiresAt(UUID organizationId, UUID reservationId) {
        String sql = """
                UPDATE reservations
                SET expires_at = NULL,
                    updated_at = NOW()
                WHERE id = ? AND organization_id = ?
                """;
        jdbcTemplate.update(sql, reservationId, organizationId);
    }

    /**
     * Updates reservation status.
     */
    public void updateReservationStatus(UUID organizationId, UUID reservationId, ReservationStatus status) {
        String sql = """
                UPDATE reservations
                SET status = ?,
                    updated_at = NOW()
                WHERE id = ? AND organization_id = ?
                """;
        jdbcTemplate.update(sql, status.name(), reservationId, organizationId);
    }

    /**
     * Retrieves allocations for an active reservation sorted canonically by product_id ASC, warehouse_id ASC.
     */
    public List<LockedAllocationInfo> getReservationAllocations(UUID organizationId, UUID reservationId) {
        String sql = """
                SELECT id, order_item_id, product_id, warehouse_id, quantity
                FROM reservation_allocations
                WHERE organization_id = ? AND reservation_id = ?
                ORDER BY product_id ASC, warehouse_id ASC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new LockedAllocationInfo(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("order_item_id")),
                UUID.fromString(rs.getString("product_id")),
                UUID.fromString(rs.getString("warehouse_id")),
                rs.getLong("quantity")
        ), organizationId, reservationId);
    }

    /**
     * Updates inventory balance for fulfillment: decreases both on_hand and reserved by quantity.
     */
    public void updateInventoryBalanceFulfill(UUID balanceId, UUID organizationId, long quantity) {
        String sql = """
                UPDATE inventory_balances
                SET on_hand = on_hand - ?,
                    reserved = reserved - ?,
                    updated_at = NOW()
                WHERE id = ? AND organization_id = ?
                """;
        jdbcTemplate.update(sql, quantity, quantity, balanceId, organizationId);
    }

    /**
     * Updates inventory balance for release/cancellation: decreases reserved by quantity.
     */
    public void updateInventoryBalanceRelease(UUID balanceId, UUID organizationId, long quantity) {
        String sql = """
                UPDATE inventory_balances
                SET reserved = reserved - ?,
                    updated_at = NOW()
                WHERE id = ? AND organization_id = ?
                """;
        jdbcTemplate.update(sql, quantity, balanceId, organizationId);
    }

    /**
     * Updates an existing locked inventory balance's reserved quantity.
     */
    public void updateInventoryBalanceReserved(UUID balanceId, UUID organizationId, long reservedDelta) {
        String sql = """
                UPDATE inventory_balances
                SET reserved = reserved + ?,
                    updated_at = NOW()
                WHERE id = ? AND organization_id = ?
                """;
        jdbcTemplate.update(sql, reservedDelta, balanceId, organizationId);
    }

    public record ExpiredCandidateInfo(UUID orderId, UUID organizationId, UUID reservationId) {}

    public record LockedReservationExpirationInfo(
            UUID id,
            String status,
            Instant expiresAt,
            boolean isExpired
    ) {}

    /**
     * Claims the next expired candidate order using PostgreSQL row locking:
     * {@code FOR UPDATE OF o SKIP LOCKED}.
     * <p>
     * Revalidates order PENDING, reservation ACTIVE, and expires_at <= NOW() according to
     * authoritative PostgreSQL database time.
     */
    public Optional<ExpiredCandidateInfo> findNextExpiredCandidate(UUID organizationId, Set<UUID> excludedOrderIds) {
        StringBuilder sql = new StringBuilder("""
                SELECT o.id AS order_id, o.organization_id, r.id AS reservation_id
                FROM orders o
                JOIN reservations r ON r.order_id = o.id AND r.organization_id = o.organization_id
                WHERE o.status = 'PENDING'
                  AND r.status = 'ACTIVE'
                  AND r.expires_at IS NOT NULL
                  AND r.expires_at <= NOW()
                """);

        List<Object> params = new ArrayList<>();
        if (organizationId != null) {
            sql.append(" AND o.organization_id = ?");
            params.add(organizationId);
        }
        if (excludedOrderIds != null && !excludedOrderIds.isEmpty()) {
            String inSql = String.join(",", Collections.nCopies(excludedOrderIds.size(), "?"));
            sql.append(" AND o.id NOT IN (").append(inSql).append(")");
            params.addAll(excludedOrderIds);
        }

        sql.append(" ORDER BY r.expires_at ASC LIMIT 1 FOR UPDATE OF o SKIP LOCKED");

        List<ExpiredCandidateInfo> results = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> new ExpiredCandidateInfo(
                UUID.fromString(rs.getString("order_id")),
                UUID.fromString(rs.getString("organization_id")),
                UUID.fromString(rs.getString("reservation_id"))
        ), params.toArray());

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    public Optional<ExpiredCandidateInfo> findNextExpiredCandidate(Set<UUID> excludedOrderIds) {
        return findNextExpiredCandidate(null, excludedOrderIds);
    }

    /**
     * Finds and locks a specific expired order candidate FOR UPDATE OF o.
     */
    public Optional<ExpiredCandidateInfo> findExpiredCandidateForOrder(UUID organizationId, UUID orderId) {
        String sql = """
                SELECT o.id AS order_id, o.organization_id, r.id AS reservation_id
                FROM orders o
                JOIN reservations r ON r.order_id = o.id AND r.organization_id = o.organization_id
                WHERE o.organization_id = ?
                  AND o.id = ?
                  AND o.status = 'PENDING'
                  AND r.status = 'ACTIVE'
                  AND r.expires_at IS NOT NULL
                  AND r.expires_at <= NOW()
                FOR UPDATE OF o
                """;
        List<ExpiredCandidateInfo> results = jdbcTemplate.query(sql, (rs, rowNum) -> new ExpiredCandidateInfo(
                UUID.fromString(rs.getString("order_id")),
                UUID.fromString(rs.getString("organization_id")),
                UUID.fromString(rs.getString("reservation_id"))
        ), organizationId, orderId);

        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    /**
     * Locks the reservation row FOR UPDATE and checks if expires_at <= NOW() under lock.
     */
    public Optional<LockedReservationExpirationInfo> lockReservationForExpiration(UUID organizationId, UUID reservationId) {
        String sql = """
                SELECT id, status, expires_at, (expires_at IS NOT NULL AND expires_at <= NOW()) AS is_expired
                FROM reservations
                WHERE organization_id = ? AND id = ?
                FOR UPDATE
                """;
        try {
            LockedReservationExpirationInfo info = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
                Timestamp ts = rs.getTimestamp("expires_at");
                return new LockedReservationExpirationInfo(
                        UUID.fromString(rs.getString("id")),
                        rs.getString("status"),
                        ts != null ? ts.toInstant() : null,
                        rs.getBoolean("is_expired")
                );
            }, organizationId, reservationId);
            return Optional.ofNullable(info);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private static class InventoryBalanceRowMapper implements RowMapper<InventoryBalance> {
        @Override
        public InventoryBalance mapRow(ResultSet rs, int rowNum) throws SQLException {
            UUID id = UUID.fromString(rs.getString("id"));
            UUID orgId = UUID.fromString(rs.getString("organization_id"));
            UUID prodId = UUID.fromString(rs.getString("product_id"));
            UUID whId = UUID.fromString(rs.getString("warehouse_id"));
            long onHand = rs.getLong("on_hand");
            long reserved = rs.getLong("reserved");

            InventoryBalance balance = new InventoryBalance(orgId, prodId, whId, onHand, reserved);
            try {
                var idField = InventoryBalance.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(balance, id);
            } catch (Exception ignored) {}

            return balance;
        }
    }
}
