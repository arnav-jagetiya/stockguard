package com.stockguard.security.dto;

import java.util.UUID;

public record UserSummaryDto(
        UUID id,
        String email,
        String fullName,
        boolean active
) {}
