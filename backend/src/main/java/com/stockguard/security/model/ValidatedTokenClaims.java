package com.stockguard.security.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable domain representation of cryptographically validated JWT claims.
 *
 * @param userId    Unique global user identifier (from {@code sub} claim)
 * @param sessionId Active session identifier in PostgreSQL (from {@code session_id} claim)
 * @param email     Normalized user email address (from {@code email} claim)
 * @param jti       Unique token identifier (from {@code jti} claim)
 * @param issuedAt  Token issuance timestamp (from {@code iat} claim)
 * @param expiresAt Token expiration timestamp (from {@code exp} claim)
 */
public record ValidatedTokenClaims(
        UUID userId,
        UUID sessionId,
        String email,
        String jti,
        Instant issuedAt,
        Instant expiresAt
) {
    public ValidatedTokenClaims {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }
        if (jti == null || jti.isBlank()) {
            throw new IllegalArgumentException("jti must not be blank");
        }
        try {
            UUID.fromString(jti.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("jti must be a valid UUID", ex);
        }
        if (issuedAt == null) {
            throw new IllegalArgumentException("issuedAt must not be null");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must not be null");
        }
    }
}
