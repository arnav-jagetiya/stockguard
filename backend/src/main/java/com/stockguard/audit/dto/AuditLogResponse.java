package com.stockguard.audit.dto;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockguard.audit.domain.AuditLog;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Data transfer object representing a single sanitized audit log entry.
 */
public record AuditLogResponse(
        UUID id,
        UUID organizationId,
        UUID actorUserId,
        String action,
        String entityType,
        UUID entityId,
        Map<String, Object> beforeState,
        Map<String, Object> afterState,
        Map<String, Object> metadata,
        Instant createdAt
) {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    public static AuditLogResponse fromDomain(AuditLog log, ObjectMapper objectMapper) {
        if (log == null) {
            return null;
        }
        return new AuditLogResponse(
                log.getId(),
                log.getOrganizationId(),
                log.getActorUserId(),
                log.getAction(),
                log.getEntityType(),
                log.getEntityId(),
                parseJson(log.getBeforeState(), objectMapper),
                parseJson(log.getAfterState(), objectMapper),
                parseJson(log.getMetadata(), objectMapper),
                log.getCreatedAt()
        );
    }

    private static Map<String, Object> parseJson(String json, ObjectMapper objectMapper) {
        if (json == null || json.isBlank() || objectMapper == null) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
