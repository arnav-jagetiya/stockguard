package com.stockguard.security.model;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Authenticated principal representing a verified user identity and active database session.
 */
public record StockGuardPrincipal(
        UUID userId,
        UUID sessionId,
        String email
) implements Serializable {

    public StockGuardPrincipal {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
    }
}
