package com.stockguard.inventory.dto;

import com.stockguard.inventory.domain.DiscrepancyStatus;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UpdateDiscrepancyRequest(
        DiscrepancyStatus status,
        UUID assignedToUserId,
        String investigationNotes,
        String resolutionReason,
        @NotNull(message = "Version is required for optimistic concurrency control")
        Integer version
) {}
