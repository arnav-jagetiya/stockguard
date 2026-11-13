package com.stockguard.organization.dto;

import com.stockguard.organization.domain.Role;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record AddMemberRequest(
        @NotNull(message = "User ID is required")
        UUID userId,

        @NotNull(message = "Role is required")
        Role role
) {
}
