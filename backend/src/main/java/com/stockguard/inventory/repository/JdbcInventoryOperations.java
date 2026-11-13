package com.stockguard.inventory.repository;

import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.dto.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
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
 * Spring JDBC repository implementing explicit row-level locking, atomic balance mutations,
 * append-only ledger insertions, and mathematical reconciliation per ADR-003, ADR-004, and ADR-008.
 */
@Repository
@Profile("!test")
public class JdbcInventoryOperations {

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public JdbcInventoryOperations(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplate = jdbcTemplateProvider != null ? jdbcTemplateProvider.getIfAvailable() : null;
    }

    public JdbcInventoryOperations(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ProductWarehouseKey(UUID productId, UUID warehouseId) implements Comparable<ProductWarehouseKey> {
        @Override
        public int compareTo(ProductWarehouseKey other) {
            int prodCmp = this.productId.compareTo(other.productId);
            if (prodCmp != 0) {
                return prodCmp;
            }
            return this.warehouseId.compareTo(other.warehouseId);
        }
    }

    /**
     * Idempotently ensures that an inventory_balances row exists for the given product and warehouse.
     * Uses ON CONFLICT DO NOTHING to eliminate insertion race conditions.
     */
    public void ensureBalanceRowExists(UUID organizationId, UUID productId, UUID warehouseId) {
        String sql = """
                INSERT INTO inventory_balances (
                    id, organization_id, product_id, warehouse_id, on_hand, reserved, updated_at
                ) VALUES (
                    gen_random_uuid(), ?, ?, ?, 0, 0, NOW()
                ) ON CONFLICT (organization_id, product_id, warehouse_id) DO NOTHING;
                """;
        jdbcTemplate.update(sql, organizationId, productId, warehouseId);
    }

    /**
     * Acquires an explicit pessimistic row-level lock (FOR UPDATE) on the balance row.
     * Ensures the row exists prior to acquiring the lock.
     */
    public InventoryBalance lockAndGetBalance(UUID organizationId, UUID productId, UUID warehouseId) {
        ensureBalanceRowExists(organizationId, productId, warehouseId);

        String sql = """
                SELECT id, organization_id, product_id, warehouse_id, on_hand, reserved, updated_at
                FROM inventory_balances
                WHERE organization_id = ? AND product_id = ? AND warehouse_id = ?
                FOR UPDATE;
                """;

        return jdbcTemplate.queryForObject(sql, new InventoryBalanceRowMapper(), organizationId, productId, warehouseId);
    }

    /**
     * Locks multiple balance rows in strictly canonical order: product_id ASC, warehouse_id ASC
     * per ADR-003 to eliminate circular-wait deadlocks.
     */
    public List<InventoryBalance> lockAndGetBalances(UUID organizationId, List<ProductWarehouseKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return Collections.emptyList();
        }

        // 1. Sort keys strictly in canonical order: product_id ASC, warehouse_id ASC
        List<ProductWarehouseKey> sortedKeys = new ArrayList<>(keys);
        Collections.sort(sortedKeys);

        // 2. Ensure each row exists, then lock sequentially in canonical order
        List<InventoryBalance> locked = new ArrayList<>(sortedKeys.size());
        for (ProductWarehouseKey key : sortedKeys) {
            locked.add(lockAndGetBalance(organizationId, key.productId(), key.warehouseId()));
        }
        return locked;
    }

    /**
     * Updates an existing locked balance row with signed deltas.
     */
    public void updateBalance(UUID balanceId, UUID organizationId, long onHandDelta, long reservedDelta) {
        String sql = """
                UPDATE inventory_balances
                SET on_hand = on_hand + ?,
                    reserved = reserved + ?,
                    updated_at = NOW()
                WHERE id = ? AND organization_id = ?;
                """;
        jdbcTemplate.update(sql, onHandDelta, reservedDelta, balanceId, organizationId);
    }

    /**
     * Checks whether an OPENING_BALANCE transaction has ever been recorded historically
     * for the given product and warehouse in this organization.
     */
    public boolean hasOpeningBalanceBeenRecorded(UUID organizationId, UUID productId, UUID warehouseId) {
        String sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM inventory_transactions t
                    JOIN inventory_ledger_entries l
                      ON t.organization_id = l.organization_id
                     AND t.id = l.transaction_id
                    WHERE t.organization_id = ?
                      AND t.transaction_type = 'OPENING_BALANCE'
                      AND l.product_id = ?
                      AND l.warehouse_id = ?
                );
                """;
        Boolean exists = jdbcTemplate.queryForObject(sql, Boolean.class, organizationId, productId, warehouseId);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * Inserts an inventory transaction header row.
     */
    public void insertTransaction(
            UUID transactionId,
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
                );
                """;
        jdbcTemplate.update(sql, transactionId, organizationId, transactionType, referenceType, referenceId, notes, actorUserId);
    }

    /**
     * Inserts an append-only ledger entry row.
     */
    public void insertLedgerEntry(
            UUID entryId,
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
                );
                """;
        jdbcTemplate.update(sql, entryId, organizationId, transactionId, entryNumber, productId, warehouseId, onHandDelta, reservedDelta);
    }

    /**
     * Retrieves an inventory transaction with all its ledger entries.
     */
    public Optional<InventoryTransactionResponse> getTransaction(UUID organizationId, UUID transactionId) {
        String txSql = """
                SELECT id, organization_id, transaction_type, reference_type, reference_id, notes, actor_user_id, created_at
                FROM inventory_transactions
                WHERE organization_id = ? AND id = ?;
                """;

        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(txSql, (rs, rowNum) -> {
                List<LedgerEntryDto> entries = getLedgerEntriesForTransaction(organizationId, transactionId);
                return new InventoryTransactionResponse(
                        UUID.fromString(rs.getString("id")),
                        UUID.fromString(rs.getString("organization_id")),
                        rs.getString("transaction_type"),
                        rs.getString("reference_type"),
                        rs.getString("reference_id") != null ? UUID.fromString(rs.getString("reference_id")) : null,
                        rs.getString("notes"),
                        rs.getString("actor_user_id") != null ? UUID.fromString(rs.getString("actor_user_id")) : null,
                        rs.getTimestamp("created_at").toInstant(),
                        entries
                );
            }, organizationId, transactionId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<LedgerEntryDto> getLedgerEntriesForTransaction(UUID organizationId, UUID transactionId) {
        String sql = """
                SELECT id, entry_number, product_id, warehouse_id, on_hand_delta, reserved_delta, created_at
                FROM inventory_ledger_entries
                WHERE organization_id = ? AND transaction_id = ?
                ORDER BY entry_number ASC;
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> new LedgerEntryDto(
                UUID.fromString(rs.getString("id")),
                rs.getInt("entry_number"),
                UUID.fromString(rs.getString("product_id")),
                UUID.fromString(rs.getString("warehouse_id")),
                rs.getLong("on_hand_delta"),
                rs.getLong("reserved_delta"),
                rs.getTimestamp("created_at").toInstant()
        ), organizationId, transactionId);
    }

    public List<InventoryTransactionResponse> listRecentTransactions(UUID organizationId, int limit) {
        String sql = """
                SELECT id, organization_id, transaction_type, reference_type, reference_id, notes, actor_user_id, created_at
                FROM inventory_transactions
                WHERE organization_id = ?
                ORDER BY created_at DESC
                LIMIT ?;
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            UUID txId = UUID.fromString(rs.getString("id"));
            List<LedgerEntryDto> entries = getLedgerEntriesForTransaction(organizationId, txId);
            return new InventoryTransactionResponse(
                    txId,
                    UUID.fromString(rs.getString("organization_id")),
                    rs.getString("transaction_type"),
                    rs.getString("reference_type"),
                    rs.getString("reference_id") != null ? UUID.fromString(rs.getString("reference_id")) : null,
                    rs.getString("notes"),
                    rs.getString("actor_user_id") != null ? UUID.fromString(rs.getString("actor_user_id")) : null,
                    rs.getTimestamp("created_at").toInstant(),
                    entries
            );
        }, organizationId, limit);
    }

    /**
     * Executes read-only mathematical reconciliation comparing inventory_balances against
     * the sum of append-only ledger entries per ADR-004.
     */
    public ReconciliationReport reconcile(UUID organizationId) {
        String sql = """
                WITH ledger_sums AS (
                    SELECT
                        organization_id,
                        product_id,
                        warehouse_id,
                        COALESCE(SUM(on_hand_delta), 0) AS sum_on_hand,
                        COALESCE(SUM(reserved_delta), 0) AS sum_reserved
                    FROM inventory_ledger_entries
                    WHERE organization_id = ?
                    GROUP BY organization_id, product_id, warehouse_id
                )
                SELECT
                    COALESCE(b.product_id, l.product_id) AS product_id,
                    COALESCE(b.warehouse_id, l.warehouse_id) AS warehouse_id,
                    COALESCE(b.on_hand, 0) AS balance_on_hand,
                    COALESCE(l.sum_on_hand, 0) AS ledger_on_hand,
                    COALESCE(b.reserved, 0) AS balance_reserved,
                    COALESCE(l.sum_reserved, 0) AS ledger_reserved,
                    CASE
                        WHEN b.id IS NULL THEN 'MISSING_BALANCE_ROW'
                        WHEN l.product_id IS NULL THEN 'MISSING_LEDGER_ENTRIES'
                        WHEN b.on_hand != l.sum_on_hand OR b.reserved != l.sum_reserved THEN 'DELTA_MISMATCH'
                        ELSE 'MATCH'
                    END AS discrepancy_status
                FROM inventory_balances b
                FULL OUTER JOIN ledger_sums l
                  ON b.organization_id = l.organization_id
                 AND b.product_id = l.product_id
                 AND b.warehouse_id = l.warehouse_id
                WHERE COALESCE(b.organization_id, l.organization_id) = ?;
                """;

        List<ReconciliationDiscrepancy> discrepancies = new ArrayList<>();
        int totalInspected = 0;
        int matchCount = 0;

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, organizationId, organizationId);
        for (Map<String, Object> row : rows) {
            totalInspected++;
            String status = (String) row.get("discrepancy_status");
            UUID productId = (UUID) row.get("product_id");
            UUID warehouseId = (UUID) row.get("warehouse_id");
            long balanceOnHand = ((Number) row.get("balance_on_hand")).longValue();
            long ledgerOnHand = ((Number) row.get("ledger_on_hand")).longValue();
            long balanceReserved = ((Number) row.get("balance_reserved")).longValue();
            long ledgerReserved = ((Number) row.get("ledger_reserved")).longValue();

            if ("MATCH".equals(status)) {
                matchCount++;
            } else {
                discrepancies.add(new ReconciliationDiscrepancy(
                        productId,
                        warehouseId,
                        balanceOnHand,
                        ledgerOnHand,
                        balanceReserved,
                        ledgerReserved,
                        status
                ));
            }
        }

        return new ReconciliationReport(
                organizationId,
                totalInspected,
                matchCount,
                discrepancies.size(),
                discrepancies,
                Instant.now()
        );
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
            // Reflect the persisted DB ID
            try {
                var idField = InventoryBalance.class.getDeclaredField("id");
                idField.setAccessible(true);
                idField.set(balance, id);
            } catch (Exception ignored) {}

            return balance;
        }
    }
}
