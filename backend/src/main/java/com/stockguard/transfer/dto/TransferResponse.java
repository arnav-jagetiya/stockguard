package com.stockguard.transfer.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        UUID organizationId,
        UUID sourceWarehouseId,
        UUID destinationWarehouseId,
        String notes,
        UUID actorUserId,
        Instant createdAt,
        Instant completedAt,
        UUID transactionId,
        List<TransferItemResponse> items
) {}
