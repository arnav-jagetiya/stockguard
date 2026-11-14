package com.stockguard.order.repository;

import com.stockguard.order.domain.OrderStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Spring JDBC data operations for high-concurrency order creation,
 * conflict-aware order number generation, and product snapshot locking.
 */
@Repository
public class JdbcOrderOperations {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private final SecureRandom secureRandom = new SecureRandom();
    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public JdbcOrderOperations(ObjectProvider<JdbcTemplate> jdbcTemplateProvider) {
        this.jdbcTemplate = jdbcTemplateProvider != null ? jdbcTemplateProvider.getIfAvailable() : null;
    }

    public JdbcOrderOperations(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record LockedProductSnapshot(UUID id, String sku, String name, boolean active) {}

    /**
     * Acquires shared locks (FOR SHARE) on products in strictly canonical ascending ID order.
     * Prevents concurrent deactivations or modifications from proceeding while capturing SKU and name snapshots.
     */
    public List<LockedProductSnapshot> lockAndGetProductsForSnapshot(UUID organizationId, List<UUID> productIds) {
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

        return jdbcTemplate.query(sql, (rs, rowNum) -> new LockedProductSnapshot(
                UUID.fromString(rs.getString("id")),
                rs.getString("sku"),
                rs.getString("name"),
                rs.getBoolean("active")
        ), params.toArray());
    }

    /**
     * Generates a collision-resistant order number with 128 bits of cryptographically secure entropy.
     * Format: ORD-YYYYMMDD-<32_HEX>
     */
    public String generateOrderNumber() {
        byte[] randomBytes = new byte[16]; // 128 bits
        secureRandom.nextBytes(randomBytes);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : randomBytes) {
            hex.append(String.format("%02X", b));
        }
        String datePart = LocalDate.now(ZoneOffset.UTC).format(DATE_FORMATTER);
        return "ORD-" + datePart + "-" + hex;
    }

    /**
     * Attempts conflict-aware order insertion using ON CONFLICT DO NOTHING RETURNING id.
     * In the rare event of an order_number collision, returns empty() without aborting
     * the active PostgreSQL transaction.
     */
    public Optional<UUID> tryInsertOrder(
            UUID id,
            UUID organizationId,
            String orderNumber,
            String customerReference,
            String externalOrderReference,
            String currency,
            BigDecimal totalAmount,
            UUID actorUserId
    ) {
        String sql = """
                INSERT INTO orders (
                    id, organization_id, order_number, customer_reference, external_order_reference,
                    status, currency, total_amount, actor_user_id, created_at, updated_at
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    'PENDING', ?, ?, ?, NOW(), NOW()
                ) ON CONFLICT (organization_id, order_number) DO NOTHING
                RETURNING id;
                """;

        List<UUID> result = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> UUID.fromString(rs.getString("id")),
                id, organizationId, orderNumber, customerReference, externalOrderReference,
                currency, totalAmount, actorUserId
        );

        if (result.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(result.get(0));
    }

    /**
     * Inserts an order line item with permanently frozen SKU, name, and unit price snapshots.
     */
    public void insertOrderItem(
            UUID id,
            UUID organizationId,
            UUID orderId,
            int lineNumber,
            UUID productId,
            String skuSnapshot,
            String nameSnapshot,
            long quantity,
            BigDecimal unitPrice
    ) {
        String sql = """
                INSERT INTO order_items (
                    id, organization_id, order_id, line_number, product_id,
                    sku_snapshot, name_snapshot, quantity, unit_price, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, NOW()
                );
                """;
        jdbcTemplate.update(
                sql,
                id, organizationId, orderId, lineNumber, productId,
                skuSnapshot, nameSnapshot, quantity, unitPrice
        );
    }

    /**
     * Updates an order's status and updated_at timestamp.
     */
    public void updateOrderStatus(UUID organizationId, UUID orderId, OrderStatus status) {
        String sql = """
                UPDATE orders
                SET status = ?,
                    updated_at = NOW()
                WHERE organization_id = ? AND id = ?
                """;
        jdbcTemplate.update(sql, status.name(), organizationId, orderId);
    }
}
