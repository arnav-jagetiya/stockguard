package com.stockguard.warehouse.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateWarehouseRequest(
        @NotBlank(message = "Warehouse name must not be blank")
        @Size(max = 255, message = "Warehouse name must not exceed 255 characters")
        String name,

        String location,

        @NotNull(message = "Priority must not be null")
        @Min(value = 1, message = "Priority must be at least 1")
        Integer priority
) {}
