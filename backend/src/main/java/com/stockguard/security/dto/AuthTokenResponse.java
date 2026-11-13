package com.stockguard.security.dto;

import java.util.List;

public record AuthTokenResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        UserSummaryDto user,
        List<OrganizationSummaryDto> availableOrganizations
) {}
