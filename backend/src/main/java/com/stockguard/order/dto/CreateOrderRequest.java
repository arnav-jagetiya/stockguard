package com.stockguard.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateOrderRequest(
        @Size(max = 255, message = "Customer reference must not exceed 255 characters")
        String customerReference,

        @Size(max = 255, message = "External order reference must not exceed 255 characters")
        String externalOrderReference,

        @Size(min = 3, max = 3, message = "Currency must be a 3-letter ISO code")
        String currency,

        @NotEmpty(message = "Order must contain at least one item")
        List<@Valid OrderItemRequest> items
) {}
