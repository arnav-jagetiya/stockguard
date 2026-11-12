package com.stockguard.security.model;

import java.util.UUID;

/**
 * Result of a successful atomic token rotation containing a new access JWT,
 * a newly issued opaque refresh token, and session metadata.
 */
public record RefreshResult(
        String accessToken,
        String rawRefreshToken,
        long expiresInSeconds,
        String tokenType,
        UUID userId,
        String email,
        UUID sessionId
) {
    public RefreshResult {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken must not be blank");
        }
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new IllegalArgumentException("rawRefreshToken must not be blank");
        }
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
    }
}
