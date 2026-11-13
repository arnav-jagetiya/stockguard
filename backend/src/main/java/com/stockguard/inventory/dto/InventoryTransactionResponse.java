package com.stockguard.inventory.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record InventoryTransactionResponse(
        UUID id,
        UUID organizationId,
        String transactionType,
        String referenceType,
        UUID referenceId,
        String notes,
        UUID actorUserId,
        Instant createdAt,
        List<LedgerEntryDto> entries
) {}
