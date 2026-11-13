package com.stockguard.security.service;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Production implementation of {@link DatabaseSessionValidator} executing a high-performance,
 * single-statement indexed join query against PostgreSQL 16.
 */
@Component
@Profile("!test")
public class JdbcDatabaseSessionValidator implements DatabaseSessionValidator {

    private static final String SQL_VALIDATE_SESSION =
            "SELECT s.revoked, s.expires_at, u.active AS user_active " +
            "FROM auth_sessions s " +
            "JOIN users u ON s.user_id = u.id " +
            "WHERE s.id = ? AND s.user_id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcDatabaseSessionValidator(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemUTC());
    }

    public JdbcDatabaseSessionValidator(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public SessionValidationResult validateSession(UUID sessionId, UUID userId) {
        if (sessionId == null || userId == null) {
            return SessionValidationResult.invalid(
                    Status.SESSION_NOT_FOUND,
                    sessionId,
                    userId,
                    "Session ID and User ID must not be null"
            );
        }

        try {
            return jdbcTemplate.queryForObject(
                    SQL_VALIDATE_SESSION,
                    (rs, rowNum) -> {
                        boolean revoked = rs.getBoolean("revoked");
                        Timestamp expTs = rs.getTimestamp("expires_at");
                        Instant expiresAt = expTs != null ? expTs.toInstant() : null;
                        boolean userActive = rs.getBoolean("user_active");

                        if (revoked) {
                            return SessionValidationResult.invalid(
                                    Status.SESSION_REVOKED,
                                    sessionId,
                                    userId,
                                    "Authentication session has been revoked"
                            );
                        }

                        Instant now = clock.instant();
                        if (expiresAt != null && now.isAfter(expiresAt)) {
                            return SessionValidationResult.invalid(
                                    Status.SESSION_EXPIRED,
                                    sessionId,
                                    userId,
                                    "Authentication session has expired"
                            );
                        }

                        if (!userActive) {
                            return SessionValidationResult.invalid(
                                    Status.USER_INACTIVE,
                                    sessionId,
                                    userId,
                                    "User account is inactive"
                            );
                        }

                        return SessionValidationResult.valid(sessionId, userId);
                    },
                    sessionId,
                    userId
            );
        } catch (EmptyResultDataAccessException ex) {
            return SessionValidationResult.invalid(
                    Status.SESSION_NOT_FOUND,
                    sessionId,
                    userId,
                    "Authentication session not found or does not belong to user"
            );
        }
    }
}
