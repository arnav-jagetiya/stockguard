package com.stockguard.security.dto;

import com.stockguard.organization.domain.Role;

import java.util.UUID;

public record OrganizationMembershipSummaryDto(
        UUID organizationId,
        String organizationName,
        Role role
) {}
