package com.stockguard.organization.dto;

import com.stockguard.organization.domain.Role;
import jakarta.validation.constraints.NotNull;

public record UpdateMemberRoleRequest(
        @NotNull(message = "Role is required")
        Role role
) {
}
