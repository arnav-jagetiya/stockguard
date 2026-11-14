package com.stockguard.transfer.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record CreateTransferRequest(
        @NotNull(message = "Source warehouse ID is required")
        UUID sourceWarehouseId,

        @NotNull(message = "Destination warehouse ID is required")
        UUID destinationWarehouseId,

        @NotEmpty(message = "Transfer items list cannot be empty")
        @Size(max = 100, message = "Transfer items list must not exceed 100 items")
        List<@Valid TransferItemRequest> items,

        @Size(max = 1000, message = "Notes cannot exceed 1000 characters")
        String notes
) {}
