package com.stockguard.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ReceiveStockRequest(
        @NotNull(message = "productId must not be null")
        UUID productId,

        @NotNull(message = "warehouseId must not be null")
        UUID warehouseId,

        @NotNull(message = "quantity must not be null")
        @Min(value = 1, message = "quantity must be greater than zero")
        Long quantity,

        UUID referenceId,

        String notes
) {}
