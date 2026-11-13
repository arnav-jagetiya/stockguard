package com.stockguard.inventory.dto;

import java.time.Instant;
import java.util.UUID;

public record LedgerEntryDto(
        UUID id,
        int entryNumber,
        UUID productId,
        UUID warehouseId,
        long onHandDelta,
        long reservedDelta,
        Instant createdAt
) {}
