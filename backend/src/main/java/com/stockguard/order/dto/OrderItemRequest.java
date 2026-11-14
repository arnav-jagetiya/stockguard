package com.stockguard.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.UUID;

public record OrderItemRequest(
        @NotNull(message = "Product ID is required")
        UUID productId,

        @NotNull(message = "Quantity is required")
        @Positive(message = "Quantity must be strictly positive")
        Long quantity,

        @NotNull(message = "Unit price is required")
        BigDecimal unitPrice
) {}
