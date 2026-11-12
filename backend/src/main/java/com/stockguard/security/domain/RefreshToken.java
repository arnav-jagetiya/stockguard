package com.stockguard.security.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Refresh token domain entity mapping the {@code refresh_tokens} table.
 * <p>
 * Database triggers and runtime grants enforce strict immutability on all fields
 * except one-way transition via {@code consumed_at} and {@code replaced_by_token_id}.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(name = "token_hash", nullable = false, length = 64, unique = true, updatable = false, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "sequence_number", nullable = false, updatable = false)
    private int sequenceNumber;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "replaced_by_token_id")
    private UUID replacedByTokenId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RefreshToken() {
        // JPA requirement
    }

    public RefreshToken(
            UUID id,
            UUID sessionId,
            String tokenHash,
            int sequenceNumber,
            Instant consumedAt,
            UUID replacedByTokenId,
            Instant expiresAt,
            Instant createdAt
    ) {
        if (id == null) {
            throw new IllegalArgumentException("Token ID must not be null");
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("Session ID must not be null");
        }
        if (tokenHash == null || !tokenHash.matches("^[0-9a-f]{64}$")) {
            throw new IllegalArgumentException("Token hash must be a 64-character lowercase hex string");
        }
        if (sequenceNumber <= 0) {
            throw new IllegalArgumentException("Sequence number must be greater than 0");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must not be null");
        }
        this.id = id;
        this.sessionId = sessionId;
        this.tokenHash = tokenHash;
        this.sequenceNumber = sequenceNumber;
        this.consumedAt = consumedAt;
        this.replacedByTokenId = replacedByTokenId;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        validateConsumedReplacementPair();
    }

    public static RefreshToken createInitial(UUID sessionId, String tokenHash, Instant expiresAt) {
        Instant now = Instant.now();
        return new RefreshToken(
                UUID.randomUUID(),
                sessionId,
                tokenHash,
                1,
                null,
                null,
                expiresAt,
                now
        );
    }

    public static RefreshToken createSuccessor(
            UUID sessionId,
            String tokenHash,
            int sequenceNumber,
            Instant expiresAt
    ) {
        Instant now = Instant.now();
        return new RefreshToken(
                UUID.randomUUID(),
                sessionId,
                tokenHash,
                sequenceNumber,
                null,
                null,
                expiresAt,
                now
        );
    }

    public boolean isConsumed() {
        return consumedAt != null || replacedByTokenId != null;
    }

    public void consume(UUID replacedByTokenId, Instant when) {
        if (isConsumed()) {
            throw new IllegalStateException("Token is already consumed");
        }
        if (replacedByTokenId == null) {
            throw new IllegalArgumentException("replacedByTokenId must not be null when consuming");
        }
        this.consumedAt = when != null ? when : Instant.now();
        this.replacedByTokenId = replacedByTokenId;
        validateConsumedReplacementPair();
    }

    private void validateConsumedReplacementPair() {
        if ((consumedAt == null && replacedByTokenId != null) ||
                (consumedAt != null && replacedByTokenId == null)) {
            throw new IllegalStateException("consumed_at and replaced_by_token_id must both be null or both be non-null");
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public int getSequenceNumber() {
        return sequenceNumber;
    }

    public Instant getConsumedAt() {
        return consumedAt;
    }

    public UUID getReplacedByTokenId() {
        return replacedByTokenId;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RefreshToken that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "RefreshToken{" +
                "id=" + id +
                ", sessionId=" + sessionId +
                ", sequenceNumber=" + sequenceNumber +
                ", isConsumed=" + isConsumed() +
                ", expiresAt=" + expiresAt +
                ", createdAt=" + createdAt +
                '}';
    }
}
