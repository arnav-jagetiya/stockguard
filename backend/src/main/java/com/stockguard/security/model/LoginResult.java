package com.stockguard.security.model;

import java.util.UUID;

/**
 * Result of a successful authentication containing access credentials,
 * opaque refresh token (to be placed in an HTTP-only cookie by future web layer),
 * and basic user summary.
 */
public record LoginResult(
        String accessToken,
        String rawRefreshToken,
        long expiresInSeconds,
        String tokenType,
        UUID userId,
        String email,
        UUID sessionId
) {
    public LoginResult {
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
