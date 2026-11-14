package com.stockguard.audit.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockguard.audit.domain.AuditLog;
import com.stockguard.audit.util.SensitiveDataMasker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * High-performance JDBC operations for {@code audit_logs} persistence and queries.
 */
@Repository
public class JdbcAuditLogOperations {

    private static final RowMapper<AuditLog> AUDIT_LOG_ROW_MAPPER = (rs, rowNum) -> {
        Timestamp createdAtTs = rs.getTimestamp("created_at");
        return new AuditLog(
                rs.getObject("id", UUID.class),
                rs.getObject("organization_id", UUID.class),
                rs.getObject("actor_user_id", UUID.class),
                rs.getString("action"),
                rs.getString("entity_type"),
                rs.getObject("entity_id", UUID.class),
                rs.getString("before_state"),
                rs.getString("after_state"),
                rs.getString("metadata"),
                createdAtTs != null ? createdAtTs.toInstant() : Instant.now()
        );
    };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public JdbcAuditLogOperations(ObjectProvider<JdbcTemplate> jdbcTemplateProvider, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplateProvider != null ? jdbcTemplateProvider.getIfAvailable() : null;
        this.objectMapper = objectMapper;
    }

    public JdbcAuditLogOperations(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Appends an audit log record directly via JDBC.
     */
    public void insertAuditLog(
            UUID id,
            UUID organizationId,
            UUID actorUserId,
            String action,
            String entityType,
            UUID entityId,
            Map<String, Object> beforeState,
            Map<String, Object> afterState,
            Map<String, Object> metadata
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(entityType, "entityType must not be null");

        UUID recordId = id != null ? id : UUID.randomUUID();

        // Apply defense-in-depth masking before serialization
        Map<String, Object> maskedBefore = SensitiveDataMasker.mask(beforeState);
        Map<String, Object> maskedAfter = SensitiveDataMasker.mask(afterState);
        Map<String, Object> maskedMeta = SensitiveDataMasker.mask(metadata);

        String beforeJson = toJson(maskedBefore);
        String afterJson = toJson(maskedAfter);
        String metaJson = toJson(maskedMeta);

        String sql = """
                INSERT INTO audit_logs (
                    id, organization_id, actor_user_id, action, entity_type, entity_id,
                    before_state, after_state, metadata, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?,
                    ?::jsonb, ?::jsonb, ?::jsonb, NOW()
                )
                """;

        jdbcTemplate.update(
                sql,
                ps -> {
                    ps.setObject(1, recordId);
                    ps.setObject(2, organizationId);
                    ps.setObject(3, actorUserId);
                    ps.setString(4, action);
                    ps.setString(5, entityType);
                    ps.setObject(6, entityId);
                    ps.setString(7, beforeJson);
                    ps.setString(8, afterJson);
                    ps.setString(9, metaJson);
                }
        );
    }

    public Optional<AuditLog> findById(UUID organizationId, UUID id) {
        String sql = """
                SELECT id, organization_id, actor_user_id, action, entity_type, entity_id,
                       before_state, after_state, metadata, created_at
                FROM audit_logs
                WHERE organization_id = ? AND id = ?
                """;
        List<AuditLog> results = jdbcTemplate.query(sql, AUDIT_LOG_ROW_MAPPER, organizationId, id);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
    }

    public List<AuditLog> findAuditLogs(
            UUID organizationId,
            String entityType,
            UUID entityId,
            String action,
            UUID actorUserId,
            Instant fromDate,
            Instant toDate,
            int limit,
            int offset
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");

        StringBuilder sql = new StringBuilder("""
                SELECT id, organization_id, actor_user_id, action, entity_type, entity_id,
                       before_state, after_state, metadata, created_at
                FROM audit_logs
                WHERE organization_id = ?
                """);

        List<Object> params = new ArrayList<>();
        params.add(organizationId);

        if (entityType != null && !entityType.isBlank()) {
            sql.append(" AND entity_type = ?");
            params.add(entityType.trim());
        }
        if (entityId != null) {
            sql.append(" AND entity_id = ?");
            params.add(entityId);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            params.add(action.trim());
        }
        if (actorUserId != null) {
            sql.append(" AND actor_user_id = ?");
            params.add(actorUserId);
        }
        if (fromDate != null) {
            sql.append(" AND created_at >= ?");
            params.add(Timestamp.from(fromDate));
        }
        if (toDate != null) {
            sql.append(" AND created_at <= ?");
            params.add(Timestamp.from(toDate));
        }

        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        int boundedLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);
        params.add(boundedLimit);
        params.add(safeOffset);

        return jdbcTemplate.query(sql.toString(), AUDIT_LOG_ROW_MAPPER, params.toArray());
    }

    public List<AuditLog> findAuditLogs(
            UUID organizationId,
            String entityType,
            String action,
            UUID actorUserId,
            Instant fromDate,
            Instant toDate,
            int limit,
            int offset
    ) {
        return findAuditLogs(organizationId, entityType, null, action, actorUserId, fromDate, toDate, limit, offset);
    }

    public long countAuditLogs(
            UUID organizationId,
            String entityType,
            UUID entityId,
            String action,
            UUID actorUserId,
            Instant fromDate,
            Instant toDate
    ) {
        Objects.requireNonNull(organizationId, "organizationId must not be null");

        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM audit_logs WHERE organization_id = ?");
        List<Object> params = new ArrayList<>();
        params.add(organizationId);

        if (entityType != null && !entityType.isBlank()) {
            sql.append(" AND entity_type = ?");
            params.add(entityType.trim());
        }
        if (entityId != null) {
            sql.append(" AND entity_id = ?");
            params.add(entityId);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            params.add(action.trim());
        }
        if (actorUserId != null) {
            sql.append(" AND actor_user_id = ?");
            params.add(actorUserId);
        }
        if (fromDate != null) {
            sql.append(" AND created_at >= ?");
            params.add(Timestamp.from(fromDate));
        }
        if (toDate != null) {
            sql.append(" AND created_at <= ?");
            params.add(Timestamp.from(toDate));
        }

        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, params.toArray());
        return count != null ? count : 0L;
    }

    public long countAuditLogs(
            UUID organizationId,
            String entityType,
            String action,
            UUID actorUserId,
            Instant fromDate,
            Instant toDate
    ) {
        return countAuditLogs(organizationId, entityType, null, action, actorUserId, fromDate, toDate);
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize audit payload to JSON", e);
        }
    }
}
