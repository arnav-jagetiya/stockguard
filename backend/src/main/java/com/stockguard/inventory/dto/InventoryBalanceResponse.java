package com.stockguard.inventory.dto;

import com.stockguard.inventory.domain.InventoryBalance;

import java.time.Instant;
import java.util.UUID;

public record InventoryBalanceResponse(
        UUID id,
        UUID organizationId,
        UUID productId,
        UUID warehouseId,
        long onHand,
        long reserved,
        long available,
        Instant updatedAt
) {
    public static InventoryBalanceResponse fromDomain(InventoryBalance balance) {
        return new InventoryBalanceResponse(
                balance.getId(),
                balance.getOrganizationId(),
                balance.getProductId(),
                balance.getWarehouseId(),
                balance.getOnHand(),
                balance.getReserved(),
                balance.getAvailable(),
                balance.getUpdatedAt()
        );
    }
}
