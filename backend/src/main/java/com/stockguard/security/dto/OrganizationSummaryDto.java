package com.stockguard.security.dto;

import com.stockguard.organization.domain.Role;

import java.util.UUID;

public record OrganizationSummaryDto(
        UUID id,
        String name,
        Role role
) {}
