package com.stockguard.warehouse.dto;

import com.stockguard.warehouse.domain.Warehouse;

import java.time.Instant;
import java.util.UUID;

public record WarehouseResponse(
        UUID id,
        UUID organizationId,
        String code,
        String normalizedCode,
        String name,
        String location,
        int priority,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static WarehouseResponse fromDomain(Warehouse warehouse) {
        return new WarehouseResponse(
                warehouse.getId(),
                warehouse.getOrganizationId(),
                warehouse.getCode(),
                warehouse.getNormalizedCode(),
                warehouse.getName(),
                warehouse.getLocation(),
                warehouse.getPriority(),
                warehouse.isActive(),
                warehouse.getCreatedAt(),
                warehouse.getUpdatedAt()
        );
    }
}
