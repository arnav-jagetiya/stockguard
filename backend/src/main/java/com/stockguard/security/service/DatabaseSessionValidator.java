package com.stockguard.security.service;

import java.util.UUID;

/**
 * Validates the database state of an authentication session and its owning user in PostgreSQL.
 * <p>
 * Implements the dual-bound session and identity check mandated by ADR-010:
 * validates that the session exists, matches the user, has not been revoked,
 * has not expired, and the user account remains active.
 */
public interface DatabaseSessionValidator {

    SessionValidationResult validateSession(UUID sessionId, UUID userId);

    enum Status {
        VALID,
        SESSION_NOT_FOUND,
        SESSION_REVOKED,
        SESSION_EXPIRED,
        USER_INACTIVE
    }

    record SessionValidationResult(
            Status status,
            UUID sessionId,
            UUID userId,
            String errorMessage
    ) {
        public boolean isValid() {
            return status == Status.VALID;
        }

        public static SessionValidationResult valid(UUID sessionId, UUID userId) {
            return new SessionValidationResult(Status.VALID, sessionId, userId, null);
        }

        public static SessionValidationResult invalid(Status status, UUID sessionId, UUID userId, String errorMessage) {
            return new SessionValidationResult(status, sessionId, userId, errorMessage);
        }
    }
}
