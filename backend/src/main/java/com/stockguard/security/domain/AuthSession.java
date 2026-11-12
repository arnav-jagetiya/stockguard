package com.stockguard.security.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Authentication session entity mapping the {@code auth_sessions} table.
 * <p>
 * Architectural Invariant: Never contains {@code organization_id} as sessions are global to a user.
 */
@Entity
@Table(name = "auth_sessions")
public class AuthSession {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "revoked", nullable = false)
    private boolean revoked;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revocation_reason", length = 100)
    private String revocationReason;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AuthSession() {
        // JPA requirement
    }

    public AuthSession(
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
    ) {
        if (id == null) {
            throw new IllegalArgumentException("Session ID must not be null");
        }
        if (userId == null) {
            throw new IllegalArgumentException("User ID must not be null");
        }
        if (expiresAt == null) {
            throw new IllegalArgumentException("expiresAt must not be null");
        }
        this.id = id;
        this.userId = userId;
        this.userAgent = userAgent != null && userAgent.length() > 500 ? userAgent.substring(0, 500) : userAgent;
        this.ipAddress = ipAddress != null && ipAddress.length() > 45 ? ipAddress.substring(0, 45) : ipAddress;
        this.revoked = revoked;
        this.revokedAt = revokedAt;
        this.revocationReason = revocationReason;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : Instant.now();
        validateRevocationPair();
    }

    public static AuthSession create(UUID userId, Instant expiresAt, String ipAddress, String userAgent) {
        Instant now = Instant.now();
        return new AuthSession(
                UUID.randomUUID(),
                userId,
                userAgent,
                ipAddress,
                false,
                null,
                null,
                expiresAt,
                now,
                now
        );
    }

    public void revoke(String reason, Instant when) {
        if (reason == null || reason.trim().isEmpty()) {
            throw new IllegalArgumentException("Revocation reason must not be blank");
        }
        this.revoked = true;
        this.revokedAt = when != null ? when : Instant.now();
        this.revocationReason = reason.trim();
        this.updatedAt = Instant.now();
        validateRevocationPair();
    }

    private void validateRevocationPair() {
        if (revoked) {
            if (revokedAt == null || revocationReason == null || revocationReason.isBlank()) {
                throw new IllegalStateException("Revoked sessions must declare revokedAt and revocationReason");
            }
        } else {
            if (revokedAt != null || revocationReason != null) {
                throw new IllegalStateException("Active sessions must not have revokedAt or revocationReason");
            }
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public boolean isRevoked() {
        return revoked;
    }

    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now());
    }

    public boolean isActive() {
        return !revoked && !isExpired();
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevocationReason() {
        return revocationReason;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AuthSession that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }

    @Override
    public String toString() {
        return "AuthSession{" +
                "id=" + id +
                ", userId=" + userId +
                ", revoked=" + revoked +
                ", revokedAt=" + revokedAt +
                ", revocationReason='" + revocationReason + '\'' +
                ", expiresAt=" + expiresAt +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
