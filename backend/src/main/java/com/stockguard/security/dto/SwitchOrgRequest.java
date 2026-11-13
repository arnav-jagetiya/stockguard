package com.stockguard.security.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SwitchOrgRequest(
        @NotNull(message = "Target organization ID must not be null")
        UUID targetOrganizationId
) {}
