package com.stockguard.audit.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockguard.audit.domain.AuditLog;
import com.stockguard.audit.dto.AuditLogPageResponse;
import com.stockguard.audit.dto.AuditLogResponse;
import com.stockguard.audit.service.AuditLogService;
import com.stockguard.security.context.OrganizationContext;
import com.stockguard.security.context.OrganizationContextHolder;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * REST controller providing tenant-isolated audit log retrieval and filtering
 * per ADR-011 and Milestone 6 specifications.
 * <p>
 * Requires {@code audit:view} permission authority (held by ADMIN and MANAGER roles).
 * Resolves authoritative tenant boundary strictly from {@link OrganizationContextHolder}.
 */
@RestController
@RequestMapping("/api/audit-logs")
@Profile("!test")
public class AuditLogController {

    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    public AuditLogController(AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
    }

    /**
     * Retrieves paginated, filtered audit records for the authenticated tenant context.
     *
     * @param action      Optional action filter (e.g. STOCK_ADJUSTED, ORDER_CREATED)
     * @param entityType  Optional entity type filter (e.g. INVENTORY_BALANCE, ORDER)
     * @param entityId    Optional entity UUID filter
     * @param actorUserId Optional actor UUID filter
     * @param startDate   Optional ISO-8601 start date timestamp
     * @param fromDate    Optional ISO-8601 start date timestamp alias
     * @param endDate     Optional ISO-8601 end date timestamp
     * @param toDate      Optional ISO-8601 end date timestamp alias
     * @param page        0-based page index (must be &gt;= 0, default 0)
     * @param size        Page size (must be between 1 and 100, default 20)
     * @return Paginated audit log response with deterministic newest-first ordering
     */
    @GetMapping
    @PreAuthorize("hasAuthority('audit:view')")
    public ResponseEntity<AuditLogPageResponse> getAuditLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) UUID entityId,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        if (page < 0) {
            throw new IllegalArgumentException("Page index must not be negative: " + page);
        }
        if (size < 1 || size > 100) {
            throw new IllegalArgumentException("Page size must be between 1 and 100: " + size);
        }

        Instant effectiveFrom = startDate != null ? startDate : fromDate;
        Instant effectiveTo = endDate != null ? endDate : toDate;

        if (effectiveFrom != null && effectiveTo != null && effectiveFrom.isAfter(effectiveTo)) {
            throw new IllegalArgumentException("startDate must not be after endDate");
        }

        OrganizationContext context = OrganizationContextHolder.requireContext();
        UUID organizationId = context.organizationId();

        int offset = page * size;
        long totalElements = auditLogService.countAuditLogs(
                organizationId,
                entityType,
                entityId,
                action,
                actorUserId,
                effectiveFrom,
                effectiveTo
        );

        List<AuditLog> logs = auditLogService.findAuditLogs(
                organizationId,
                entityType,
                entityId,
                action,
                actorUserId,
                effectiveFrom,
                effectiveTo,
                size,
                offset
        );

        List<AuditLogResponse> content = logs.stream()
                .map(log -> AuditLogResponse.fromDomain(log, objectMapper))
                .toList();

        AuditLogPageResponse response = AuditLogPageResponse.of(content, page, size, totalElements);
        return ResponseEntity.ok(response);
    }
}
