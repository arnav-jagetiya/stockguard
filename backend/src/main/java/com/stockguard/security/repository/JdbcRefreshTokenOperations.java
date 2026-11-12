package com.stockguard.security.repository;

import com.stockguard.security.domain.RefreshToken;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring JDBC repository for security-sensitive refresh token state transitions and row-level locking.
 * <p>
 * Ensures explicit column-level SQL statements matching the restricted permissions of {@code stockguard_app}
 * (which permits UPDATE strictly on {@code consumed_at} and {@code replaced_by_token_id}).
 */
@Repository
@org.springframework.context.annotation.Profile("!test")
public class JdbcRefreshTokenOperations {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRefreshTokenOperations(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Non-locking initial lookup to identify the parent session ID from a token hash.
     */
    public Optional<UUID> findSessionIdByTokenHash(String tokenHash) {
        String sql = "SELECT session_id FROM refresh_tokens WHERE token_hash = ?";
        try {
            UUID sessionId = jdbcTemplate.queryForObject(sql, UUID.class, tokenHash);
            return Optional.ofNullable(sessionId);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * Step 3 of rotation: Acquire canonical row lock on auth_sessions row FIRST.
     */
    public Optional<AuthSessionSnapshot> lockSessionById(UUID sessionId) {
        String sql = "SELECT id, user_id, user_agent, ip_address, revoked, revoked_at, " +
                "revocation_reason, expires_at, created_at, updated_at " +
                "FROM auth_sessions WHERE id = ? FOR UPDATE";
        try {
            AuthSessionSnapshot session = jdbcTemplate.queryForObject(sql, new AuthSessionRowMapper(), sessionId);
            return Optional.ofNullable(session);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * Step 4 of rotation: Acquire canonical row lock on refresh_tokens row SECOND.
     */
    public Optional<RefreshTokenSnapshot> lockTokenByHash(String tokenHash) {
        String sql = "SELECT id, session_id, token_hash, sequence_number, consumed_at, " +
                "replaced_by_token_id, expires_at, created_at " +
                "FROM refresh_tokens WHERE token_hash = ? FOR UPDATE";
        try {
            RefreshTokenSnapshot token = jdbcTemplate.queryForObject(sql, new RefreshTokenRowMapper(), tokenHash);
            return Optional.ofNullable(token);
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /**
     * Step 6 of rotation: Mark predecessor token consumed.
     * Uses explicit SQL touching ONLY columns permitted for UPDATE (consumed_at, replaced_by_token_id).
     */
    public int consumeToken(UUID tokenId, UUID replacedByTokenId, Instant consumedAt) {
        String sql = "UPDATE refresh_tokens " +
                "SET consumed_at = ?, replaced_by_token_id = ? " +
                "WHERE id = ? AND consumed_at IS NULL";
        return jdbcTemplate.update(
                sql,
                Timestamp.from(consumedAt),
                replacedByTokenId,
                tokenId
        );
    }

    /**
     * Step 7 of rotation: Insert successor token into refresh_tokens.
     */
    public void insertToken(RefreshToken token) {
        String sql = "INSERT INTO refresh_tokens " +
                "(id, session_id, token_hash, sequence_number, expires_at, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)";
        jdbcTemplate.update(
                sql,
                token.getId(),
                token.getSessionId(),
                token.getTokenHash(),
                token.getSequenceNumber(),
                Timestamp.from(token.getExpiresAt()),
                Timestamp.from(token.getCreatedAt())
        );
    }

    /**
     * Updates session updated_at timestamp.
     */
    public void updateSessionUpdatedAt(UUID sessionId, Instant updatedAt) {
        String sql = "UPDATE auth_sessions SET updated_at = ? WHERE id = ?";
        jdbcTemplate.update(sql, Timestamp.from(updatedAt), sessionId);
    }

    /**
     * Revokes an authentication session family.
     */
    public int revokeSession(UUID sessionId, String reason, Instant revokedAt) {
        String sql = "UPDATE auth_sessions " +
                "SET revoked = TRUE, revoked_at = ?, revocation_reason = ?, updated_at = ? " +
                "WHERE id = ? AND revoked = FALSE";
        return jdbcTemplate.update(
                sql,
                Timestamp.from(revokedAt),
                reason,
                Timestamp.from(revokedAt),
                sessionId
        );
    }

    /**
     * Transaction-scoped advisory lock for multi-instance synchronization (e.g. bootstrap).
     */
    public boolean tryAdvisoryXactLock(long lockKey) {
        Boolean acquired = jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, lockKey);
        return Boolean.TRUE.equals(acquired);
    }

    // --- Immutable Snapshots and RowMappers ---

    public record AuthSessionSnapshot(
            UUID id,
            UUID userId,
            String userAgent,
            String ipAddress,
            boolean revoked,
            Instant revokedAt,
            String revocationReason,
            Instant expiresAt,
            Instant createdAt,
            Instant updatedAt
    ) {}

    public record RefreshTokenSnapshot(
            UUID id,
            UUID sessionId,
            String tokenHash,
            int sequenceNumber,
            Instant consumedAt,
            UUID replacedByTokenId,
            Instant expiresAt,
            Instant createdAt
    ) {
        public boolean isConsumed() {
            return consumedAt != null || replacedByTokenId != null;
        }
    }

    private static class AuthSessionRowMapper implements RowMapper<AuthSessionSnapshot> {
        @Override
        public AuthSessionSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp revokedTs = rs.getTimestamp("revoked_at");
            Timestamp expTs = rs.getTimestamp("expires_at");
            Timestamp createdTs = rs.getTimestamp("created_at");
            Timestamp updatedTs = rs.getTimestamp("updated_at");

            return new AuthSessionSnapshot(
                    rs.getObject("id", UUID.class),
                    rs.getObject("user_id", UUID.class),
                    rs.getString("user_agent"),
                    rs.getString("ip_address"),
                    rs.getBoolean("revoked"),
                    revokedTs != null ? revokedTs.toInstant() : null,
                    rs.getString("revocation_reason"),
                    expTs != null ? expTs.toInstant() : null,
                    createdTs != null ? createdTs.toInstant() : null,
                    updatedTs != null ? updatedTs.toInstant() : null
            );
        }
    }

    private static class RefreshTokenRowMapper implements RowMapper<RefreshTokenSnapshot> {
        @Override
        public RefreshTokenSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
            Timestamp consumedTs = rs.getTimestamp("consumed_at");
            Timestamp expTs = rs.getTimestamp("expires_at");
            Timestamp createdTs = rs.getTimestamp("created_at");

            return new RefreshTokenSnapshot(
                    rs.getObject("id", UUID.class),
                    rs.getObject("session_id", UUID.class),
                    rs.getString("token_hash"),
                    rs.getInt("sequence_number"),
                    consumedTs != null ? consumedTs.toInstant() : null,
                    rs.getObject("replaced_by_token_id", UUID.class),
                    expTs != null ? expTs.toInstant() : null,
                    createdTs != null ? createdTs.toInstant() : null
            );
        }
    }
}
