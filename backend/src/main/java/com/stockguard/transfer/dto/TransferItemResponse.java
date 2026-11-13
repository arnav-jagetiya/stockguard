package com.stockguard.transfer.dto;

import java.time.Instant;
import java.util.UUID;

public record TransferItemResponse(
        UUID id,
        UUID productId,
        long quantity,
        Instant createdAt
) {}
