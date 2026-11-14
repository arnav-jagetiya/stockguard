package com.stockguard.reservation.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateReservationRequest(
        @NotNull(message = "Order ID must be specified")
        UUID orderId
) {}
