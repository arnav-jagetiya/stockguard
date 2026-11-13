package com.stockguard.security.dto;

import com.stockguard.organization.domain.Role;

import java.util.Set;
import java.util.UUID;

public record SwitchOrgResponse(
        UUID organizationId,
        String organizationName,
        Role role,
        Set<String> permissions
) {}
