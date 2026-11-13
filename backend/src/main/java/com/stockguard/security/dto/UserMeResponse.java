package com.stockguard.security.dto;

import java.util.List;
import java.util.UUID;

public record UserMeResponse(
        UUID id,
        String email,
        String fullName,
        boolean active,
        List<OrganizationMembershipSummaryDto> memberships
) {}
