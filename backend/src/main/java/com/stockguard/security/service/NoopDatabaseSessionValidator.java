package com.stockguard.security.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Test-profile stub for {@link DatabaseSessionValidator} when running isolated mock-MVC tests
 * without a live PostgreSQL DataSource.
 */
@Component
@Profile("test")
public class NoopDatabaseSessionValidator implements DatabaseSessionValidator {

    @Override
    public SessionValidationResult validateSession(UUID sessionId, UUID userId) {
        return SessionValidationResult.valid(sessionId, userId);
    }
}
