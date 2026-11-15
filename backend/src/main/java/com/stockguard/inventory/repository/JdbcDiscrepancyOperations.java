package com.stockguard.inventory.repository;

import com.stockguard.inventory.domain.DiscrepancyStatus;
import com.stockguard.inventory.domain.DiscrepancyType;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * High-performance direct JDBC operations for reconciliation discrepancies per ADR-012.
 */
@Repository
@Profile("!test")
public class JdbcDiscrepancyOperations {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public JdbcDiscrepancyOperations(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record ActiveDiscrepancyRow(
            UUID id,
            UUID organizationId,
            UUID productId,
            UUID warehouseId,
            DiscrepancyStatus status,
            int consecutiveDetectionCount,
            int version,
            Instant lastDetectedAt
    ) {}

    public record UpsertResult(
            UUID id,
            DiscrepancyStatus status,
            int consecutiveDetectionCount,
            boolean isNewInsert
    ) {}

    /**
     * Concurrency-safe atomic insert-or-update using PostgreSQL partial unique index.
     * Guarantees zero duplicate active discrepancies for the same (org, product, warehouse) position.
     */
    public UpsertResult upsertActiveDiscrepancy(
            UUID organizationId,
            UUID productId,
            UUID warehouseId,
            DiscrepancyType discrepancyType,
            long observedBalanceOnHand,
            long observedLedgerOnHand,
            long observedBalanceReserved,
            long observedLedgerReserved
    ) {
        return upsertActiveDiscrepancy(
                organizationId, productId, warehouseId, discrepancyType,
                observedBalanceOnHand, observedLedgerOnHand,
                observedBalanceReserved, observedLedgerReserved,
                null, null, true
        );
    }

    /**
     * Concurrency-safe atomic insert-or-update using PostgreSQL partial unique index.
     * Guarantees zero duplicate active discrepancies for the same (org, product, warehouse) position.
     * When allowUnconditionalUpdate is false, updates only if the existing row matches both expectedExistingId
     * and expectedVersion, preventing older sweeps from overwriting newer discrepancies created or modified
     * after the snapshot.
     */
    public UpsertResult upsertActiveDiscrepancy(
            UUID organizationId,
            UUID productId,
            UUID warehouseId,
            DiscrepancyType discrepancyType,
            long observedBalanceOnHand,
            long observedLedgerOnHand,
            long observedBalanceReserved,
            long observedLedgerReserved,
            UUID expectedExistingId,
            Integer expectedVersion,
            boolean allowUnconditionalUpdate
    ) {
        // If an existing active discrepancy was observed in Tx1 snapshot, Tx2 is strictly an UPDATE.
        // It must never perform an INSERT that could resurrect a concurrently resolved discrepancy (Scenario A).
        if (expectedExistingId != null) {
            String updateSql = """
                UPDATE reconciliation_discrepancies
                SET discrepancy_type = :discrepancyType,
                    observed_balance_on_hand = :observedBalanceOnHand,
                    observed_ledger_on_hand = :observedLedgerOnHand,
                    observed_balance_reserved = :observedBalanceReserved,
                    observed_ledger_reserved = :observedLedgerReserved,
                    consecutive_detection_count = consecutive_detection_count + 1,
                    last_detected_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP,
                    version = version + 1
                WHERE id = :expectedExistingId
                  AND (:allowUnconditionalUpdate = TRUE OR version = :expectedVersion)
                  AND status IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING')
                RETURNING id, status, consecutive_detection_count;
                """;

            MapSqlParameterSource updateParams = new MapSqlParameterSource()
                    .addValue("expectedExistingId", expectedExistingId)
                    .addValue("expectedVersion", expectedVersion)
                    .addValue("allowUnconditionalUpdate", allowUnconditionalUpdate)
                    .addValue("discrepancyType", discrepancyType.name())
                    .addValue("observedBalanceOnHand", observedBalanceOnHand)
                    .addValue("observedLedgerOnHand", observedLedgerOnHand)
                    .addValue("observedBalanceReserved", observedBalanceReserved)
                    .addValue("observedLedgerReserved", observedLedgerReserved);

            List<UpsertResult> updateResults = jdbcTemplate.query(updateSql, updateParams, (rs, rowNum) -> new UpsertResult(
                    rs.getObject("id", UUID.class),
                    DiscrepancyStatus.valueOf(rs.getString("status")),
                    rs.getInt("consecutive_detection_count"),
                    false
            ));

            if (!updateResults.isEmpty()) {
                return updateResults.get(0);
            }

            // Discrepancy was not updated because version changed concurrently or it transitioned to a terminal state.
            // Return null so the caller does not silently report a successful update or write false audit entries.
            return null;
        }

        String sql = """
            INSERT INTO reconciliation_discrepancies (
                id, organization_id, product_id, warehouse_id,
                discrepancy_type, observed_balance_on_hand, observed_ledger_on_hand,
                observed_balance_reserved, observed_ledger_reserved,
                status, consecutive_detection_count, first_detected_at, last_detected_at,
                version, created_at, updated_at
            ) VALUES (
                :id, :organizationId, :productId, :warehouseId,
                :discrepancyType, :observedBalanceOnHand, :observedLedgerOnHand,
                :observedBalanceReserved, :observedLedgerReserved,
                'OPEN', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
            )
            ON CONFLICT (organization_id, product_id, warehouse_id)
            WHERE status IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING')
            DO UPDATE SET
                discrepancy_type = EXCLUDED.discrepancy_type,
                observed_balance_on_hand = EXCLUDED.observed_balance_on_hand,
                observed_ledger_on_hand = EXCLUDED.observed_ledger_on_hand,
                observed_balance_reserved = EXCLUDED.observed_balance_reserved,
                observed_ledger_reserved = EXCLUDED.observed_ledger_reserved,
                consecutive_detection_count = reconciliation_discrepancies.consecutive_detection_count + 1,
                last_detected_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP,
                version = reconciliation_discrepancies.version + 1
            WHERE (:allowUnconditionalUpdate = TRUE)
               OR (reconciliation_discrepancies.id = :expectedExistingId
                   AND reconciliation_discrepancies.version = :expectedVersion)
            RETURNING id, status, consecutive_detection_count, (xmax = 0) AS is_new_insert;
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("organizationId", organizationId)
                .addValue("productId", productId)
                .addValue("warehouseId", warehouseId)
                .addValue("discrepancyType", discrepancyType.name())
                .addValue("observedBalanceOnHand", observedBalanceOnHand)
                .addValue("observedLedgerOnHand", observedLedgerOnHand)
                .addValue("observedBalanceReserved", observedBalanceReserved)
                .addValue("observedLedgerReserved", observedLedgerReserved)
                .addValue("expectedExistingId", expectedExistingId)
                .addValue("expectedVersion", expectedVersion)
                .addValue("allowUnconditionalUpdate", allowUnconditionalUpdate);

        List<UpsertResult> results = jdbcTemplate.query(sql, params, (rs, rowNum) -> new UpsertResult(
                rs.getObject("id", UUID.class),
                DiscrepancyStatus.valueOf(rs.getString("status")),
                rs.getInt("consecutive_detection_count"),
                rs.getBoolean("is_new_insert")
        ));

        if (!results.isEmpty()) {
            return results.get(0);
        }

        // Row was not inserted or updated because an existing active discrepancy was created concurrently after the snapshot.
        // Return null so the caller does not silently report a successful update or write false audit entries.
        return null;
    }

    /**
     * Finds active discrepancy by organization and position.
     */
    public Optional<ActiveDiscrepancyRow> findActiveByPosition(UUID organizationId, UUID productId, UUID warehouseId) {
        String sql = """
            SELECT id, organization_id, product_id, warehouse_id, status, consecutive_detection_count, version, last_detected_at
            FROM reconciliation_discrepancies
            WHERE organization_id = :organizationId
              AND product_id = :productId
              AND warehouse_id = :warehouseId
              AND status IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING');
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("organizationId", organizationId)
                .addValue("productId", productId)
                .addValue("warehouseId", warehouseId);

        List<ActiveDiscrepancyRow> rows = jdbcTemplate.query(sql, params, this::mapActiveRow);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Retrieves all currently active discrepancies for the tenant.
     */
    public List<ActiveDiscrepancyRow> findActiveDiscrepancies(UUID organizationId) {
        String sql = """
            SELECT id, organization_id, product_id, warehouse_id, status, consecutive_detection_count, version, last_detected_at
            FROM reconciliation_discrepancies
            WHERE organization_id = :organizationId
              AND status IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING');
            """;

        MapSqlParameterSource params = new MapSqlParameterSource("organizationId", organizationId);
        return jdbcTemplate.query(sql, params, this::mapActiveRow);
    }

    /**
     * Auto-resolves an OPEN discrepancy when parity is verified.
     * Enforces that the discrepancy was detected on or before the report observation timestamp
     * to prevent a stale clean-parity report from resolving a newer mismatch.
     */
    public boolean autoResolveOpenDiscrepancy(UUID id, int version, String reason, Instant reportObservationTime) {
        String sql = """
            UPDATE reconciliation_discrepancies
            SET status = 'RESOLVED',
                resolution_source = 'SYSTEM',
                resolved_by_user_id = NULL,
                resolved_at = CURRENT_TIMESTAMP,
                resolution_reason = :reason,
                last_verified_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = :id
              AND version = :version
              AND status = 'OPEN'
              AND last_detected_at <= :reportObservationTime;
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("version", version)
                .addValue("reason", reason)
                .addValue("reportObservationTime", Timestamp.from(reportObservationTime));

        return jdbcTemplate.update(sql, params) > 0;
    }

    public boolean autoResolveOpenDiscrepancy(UUID id, int version, String reason) {
        return autoResolveOpenDiscrepancy(id, version, reason, Instant.now());
    }

    /**
     * Updates last_verified_at for ACKNOWLEDGED or INVESTIGATING discrepancies on clean observation.
     * Preserves status, notes, assignment, and consecutive_detection_count.
     * Enforces that the discrepancy was detected on or before the report observation timestamp.
     */
    public boolean updateParityVerified(UUID id, int version, Instant reportObservationTime) {
        String sql = """
            UPDATE reconciliation_discrepancies
            SET last_verified_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = :id
              AND version = :version
              AND last_detected_at <= :reportObservationTime;
            """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("version", version)
                .addValue("reportObservationTime", Timestamp.from(reportObservationTime));

        return jdbcTemplate.update(sql, params) > 0;
    }

    public boolean updateParityVerified(UUID id, int version) {
        return updateParityVerified(id, version, Instant.now());
    }

    private ActiveDiscrepancyRow mapActiveRow(ResultSet rs, int rowNum) throws SQLException {
        Timestamp lastDetectedTs = rs.getTimestamp("last_detected_at");
        return new ActiveDiscrepancyRow(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getObject("warehouse_id", UUID.class),
                DiscrepancyStatus.valueOf(rs.getString("status")),
                rs.getInt("consecutive_detection_count"),
                rs.getInt("version"),
                lastDetectedTs != null ? lastDetectedTs.toInstant() : null
        );
    }
}
