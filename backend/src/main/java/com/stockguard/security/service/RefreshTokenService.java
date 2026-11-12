package com.stockguard.security.service;

import com.stockguard.security.crypto.TokenCryptoUtils;
import com.stockguard.security.domain.AuthSession;
import com.stockguard.security.domain.RefreshToken;
import com.stockguard.security.exception.InvalidTokenException;
import com.stockguard.security.exception.SessionExpiredException;
import com.stockguard.security.exception.SessionRevokedException;
import com.stockguard.security.exception.TokenExpiredException;
import com.stockguard.security.exception.TokenReuseException;
import com.stockguard.security.repository.AuthSessionRepository;
import com.stockguard.security.repository.JdbcRefreshTokenOperations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing the database-backed refresh token and session lifecycle.
 * <p>
 * Enforces strict single-use rotation, canonical row locking (auth_sessions FIRST, refresh_tokens SECOND),
 * sliding 7-day token lifetimes bounded by 30-day absolute session lifetimes, and committed session-family
 * revocation upon replay detection.
 */
@Service
@org.springframework.context.annotation.Profile("!test")
public class RefreshTokenService {

    private static final Duration SLIDING_TOKEN_LIFETIME = Duration.ofDays(7);
    private static final Duration ABSOLUTE_SESSION_LIFETIME = Duration.ofDays(30);

    private final JdbcRefreshTokenOperations jdbcOperations;
    private final AuthSessionRepository authSessionRepository;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public RefreshTokenService(
            JdbcRefreshTokenOperations jdbcOperations,
            AuthSessionRepository authSessionRepository,
            PlatformTransactionManager transactionManager
    ) {
        this(jdbcOperations, authSessionRepository, transactionManager, Clock.systemUTC());
    }

    public RefreshTokenService(
            JdbcRefreshTokenOperations jdbcOperations,
            AuthSessionRepository authSessionRepository,
            PlatformTransactionManager transactionManager,
            Clock clock
    ) {
        this.jdbcOperations = jdbcOperations;
        this.authSessionRepository = authSessionRepository;
        this.transactionManager = transactionManager;
        this.clock = clock;
    }

    /**
     * Creates an initial authentication session and initial active refresh token.
     *
     * @param userId    Authenticated user UUID
     * @param ipAddress Client IP address (optional)
     * @param userAgent Client User-Agent header (optional)
     * @return InitialSessionResult containing session entity and raw opaque token
     */
    public InitialSessionResult createInitialSession(UUID userId, String ipAddress, String userAgent) {
        Instant now = clock.instant();
        Instant sessionExpiresAt = now.plus(ABSOLUTE_SESSION_LIFETIME);
        Instant tokenExpiresAt = calculateTokenExpiry(now, sessionExpiresAt);

        String rawToken = TokenCryptoUtils.generateOpaqueToken();
        String tokenHash = TokenCryptoUtils.hashToken(rawToken);

        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setName("createInitialSession");
        TransactionStatus status = transactionManager.getTransaction(def);

        try {
            AuthSession session = AuthSession.create(userId, sessionExpiresAt, ipAddress, userAgent);
            session = authSessionRepository.saveAndFlush(session);

            RefreshToken initialToken = RefreshToken.createInitial(session.getId(), tokenHash, tokenExpiresAt);
            jdbcOperations.insertToken(initialToken);

            transactionManager.commit(status);
            return new InitialSessionResult(session, rawToken);
        } catch (Exception ex) {
            if (!status.isCompleted()) {
                transactionManager.rollback(status);
            }
            throw ex;
        }
    }

    /**
     * Atomically rotates a refresh token following the approved canonical lock sequence.
     * <p>
     * Lock Sequence:
     * <ol>
     *   <li>Hash presented raw token</li>
     *   <li>Non-locking session lookup</li>
     *   <li>Lock auth_sessions row FIRST (FOR UPDATE)</li>
     *   <li>Lock refresh_tokens row SECOND (FOR UPDATE)</li>
     *   <li>Revalidate state</li>
     *   <li>Replay check -> commit revocation if consumed</li>
     *   <li>Normal rotation -> consume predecessor, insert successor, commit atomically</li>
     * </ol>
     *
     * @param rawToken Presented raw opaque refresh token
     * @return RotationOutcome containing session ID, user ID, and newly issued raw token
     */
    public RotationOutcome rotateRefreshToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new InvalidTokenException("Refresh token must not be blank");
        }

        Instant now = clock.instant();
        String tokenHash = TokenCryptoUtils.hashToken(rawToken);

        // Step 2: Non-locking lookup to identify session
        UUID sessionId = jdbcOperations.findSessionIdByTokenHash(tokenHash)
                .orElseThrow(() -> new InvalidTokenException("Invalid refresh token"));

        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setName("rotateRefreshToken");
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        def.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        TransactionStatus status = transactionManager.getTransaction(def);

        try {
            // Step 3: Lock auth_sessions row FIRST
            JdbcRefreshTokenOperations.AuthSessionSnapshot session = jdbcOperations.lockSessionById(sessionId)
                    .orElseThrow(() -> new InvalidTokenException("Associated session not found"));

            // Step 4: Lock refresh_tokens row SECOND
            JdbcRefreshTokenOperations.RefreshTokenSnapshot token = jdbcOperations.lockTokenByHash(tokenHash)
                    .orElseThrow(() -> new InvalidTokenException("Refresh token record not found"));

            // Step 5: State Revalidation
            if (session.revoked()) {
                throw new SessionRevokedException("Session has been revoked");
            }
            if (now.isAfter(session.expiresAt())) {
                throw new SessionExpiredException("Session has expired");
            }
            if (now.isAfter(token.expiresAt())) {
                throw new TokenExpiredException("Refresh token has expired");
            }

            // Step 6: Replay Detection Check
            if (token.isConsumed()) {
                // TOKEN REUSE DETECTED: Commit revocation of the entire session family
                jdbcOperations.revokeSession(session.id(), "TOKEN_REUSE_DETECTED", now);
                transactionManager.commit(status);
                throw new TokenReuseException("Refresh token replay detected. Session family has been revoked.");
            }

            // Step 7: Normal Rotation
            String newRawToken = TokenCryptoUtils.generateOpaqueToken();
            String newTokenHash = TokenCryptoUtils.hashToken(newRawToken);
            UUID successorId = UUID.randomUUID();
            Instant successorExpiresAt = calculateTokenExpiry(now, session.expiresAt());

            if (!successorExpiresAt.isAfter(now)) {
                throw new SessionExpiredException("Session absolute lifetime reached");
            }

            // Consume predecessor
            int updated = jdbcOperations.consumeToken(token.id(), successorId, now);
            if (updated != 1) {
                throw new IllegalStateException("Failed to consume predecessor token: expected 1 row updated, got " + updated);
            }

            // Insert successor
            RefreshToken successor = new RefreshToken(
                    successorId,
                    session.id(),
                    newTokenHash,
                    token.sequenceNumber() + 1,
                    null,
                    null,
                    successorExpiresAt,
                    now
            );
            jdbcOperations.insertToken(successor);

            // Update session updated_at
            jdbcOperations.updateSessionUpdatedAt(session.id(), now);

            transactionManager.commit(status);
            return new RotationOutcome(session.id(), session.userId(), newRawToken);
        } catch (TokenReuseException ex) {
            // Revocation has already been committed to the database; rethrow
            throw ex;
        } catch (Exception ex) {
            if (!status.isCompleted()) {
                transactionManager.rollback(status);
            }
            throw ex;
        }
    }

    /**
     * Revokes a session family identified by a presented refresh token.
     */
    public void revokeSessionByToken(String rawToken, String reason) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        String tokenHash = TokenCryptoUtils.hashToken(rawToken);
        Optional<UUID> sessionIdOpt = jdbcOperations.findSessionIdByTokenHash(tokenHash);
        if (sessionIdOpt.isEmpty()) {
            return;
        }
        revokeSessionById(sessionIdOpt.get(), reason != null ? reason : "LOGOUT");
    }

    /**
     * Revokes a session family by its session UUID.
     */
    public void revokeSessionById(UUID sessionId, String reason) {
        Instant now = clock.instant();
        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setName("revokeSessionById");
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        def.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        TransactionStatus status = transactionManager.getTransaction(def);

        try {
            jdbcOperations.lockSessionById(sessionId);
            jdbcOperations.revokeSession(sessionId, reason != null ? reason : "REVOKED", now);
            transactionManager.commit(status);
        } catch (Exception ex) {
            if (!status.isCompleted()) {
                transactionManager.rollback(status);
            }
            throw ex;
        }
    }

    private Instant calculateTokenExpiry(Instant now, Instant sessionExpiresAt) {
        Instant slidingExpiry = now.plus(SLIDING_TOKEN_LIFETIME);
        return slidingExpiry.isBefore(sessionExpiresAt) ? slidingExpiry : sessionExpiresAt;
    }

    public record InitialSessionResult(AuthSession session, String rawRefreshToken) {}

    public record RotationOutcome(UUID sessionId, UUID userId, String rawRefreshToken) {}
}
