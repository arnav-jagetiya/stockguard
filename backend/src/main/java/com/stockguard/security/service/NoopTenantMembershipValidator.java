package com.stockguard.security.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Test profile stub allowing application context initialization without a live database.
 */
@Service
@Profile("test")
public class NoopTenantMembershipValidator implements TenantMembershipValidator {

    @Override
    public Optional<TenantMembershipDetails> validateMembership(UUID userId, UUID organizationId) {
        return Optional.empty();
    }
}
