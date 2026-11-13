package com.stockguard.inventory.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AdjustStockRequest(
        @NotNull(message = "productId must not be null")
        UUID productId,

        @NotNull(message = "warehouseId must not be null")
        UUID warehouseId,

        @NotNull(message = "delta must not be null")
        Long delta,

        @NotBlank(message = "Adjustment reason is mandatory")
        @Size(max = 1000, message = "Reason must not exceed 1000 characters")
        String reason,

        UUID referenceId
) {
    @AssertTrue(message = "Adjustment delta must not be zero")
    public boolean isNonZeroDelta() {
        return delta != null && delta != 0L;
    }
}
