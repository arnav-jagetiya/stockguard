package com.stockguard.organization.dto;

import com.stockguard.organization.domain.Role;

import java.time.Instant;
import java.util.UUID;

public record OrganizationMemberResponse(
        UUID id,
        UUID organizationId,
        UUID userId,
        Role role,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
}
