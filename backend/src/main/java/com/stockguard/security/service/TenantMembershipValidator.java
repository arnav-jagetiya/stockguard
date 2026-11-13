package com.stockguard.security.service;

import com.stockguard.organization.domain.Role;

import java.util.Optional;
import java.util.UUID;

/**
 * Authoritative validator that verifies tenant membership against PostgreSQL.
 */
public interface TenantMembershipValidator {

    record TenantMembershipDetails(
            UUID organizationId,
            UUID userId,
            Role role,
            boolean active
    ) {
    }

    Optional<TenantMembershipDetails> validateMembership(UUID userId, UUID organizationId);
}
