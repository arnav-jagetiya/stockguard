package com.stockguard.audit.dto;

import java.util.Collections;
import java.util.List;

/**
 * Paginated envelope for audit log retrieval API responses.
 */
public record AuditLogPageResponse(
        List<AuditLogResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean first,
        boolean last
) {
    public static AuditLogPageResponse of(
            List<AuditLogResponse> content,
            int page,
            int size,
            long totalElements
    ) {
        List<AuditLogResponse> safeContent = content != null ? content : Collections.emptyList();
        int safeSize = Math.max(1, size);
        int totalPages = (int) Math.ceil((double) totalElements / safeSize);
        boolean first = page <= 0;
        boolean last = page >= totalPages - 1 || totalPages == 0;
        return new AuditLogPageResponse(
                Collections.unmodifiableList(safeContent),
                page,
                safeSize,
                totalElements,
                totalPages,
                first,
                last
        );
    }
}
