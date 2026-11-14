package com.stockguard.reservation.dto;

import com.stockguard.reservation.domain.ReservationAllocation;
import java.time.Instant;
import java.util.UUID;

public record ReservationAllocationResponse(
        UUID id,
        UUID orderItemId,
        UUID productId,
        UUID warehouseId,
        long quantity,
        Instant createdAt
) {
    public static ReservationAllocationResponse fromDomain(ReservationAllocation allocation) {
        return new ReservationAllocationResponse(
                allocation.getId(),
                allocation.getOrderItemId(),
                allocation.getProductId(),
                allocation.getWarehouseId(),
                allocation.getQuantity(),
                allocation.getCreatedAt()
        );
    }
}
