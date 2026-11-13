package com.stockguard.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProductRequest(
        @NotBlank(message = "Product name must not be blank")
        @Size(max = 255, message = "Product name must not exceed 255 characters")
        String name,

        String description
) {}
