package com.stockguard.security.domain;

import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DomainEntitiesTest {

    @Test
    @DisplayName("User entity enforces normalized email invariants")
    void userEntityEnforcesEmailNormalization() {
        User user = User.create("  Admin@StockGuard.COM  ", "$2a$12$somevalidhash");

        assertThat(user.getEmail()).isEqualTo("Admin@StockGuard.COM");
        assertThat(user.getNormalizedEmail()).isEqualTo("admin@stockguard.com");
        assertThat(user.isActive()).isTrue();
        assertThat(user.getId()).isNotNull();

        assertThatThrownBy(() -> User.create("   ", "hash"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("AuthSession entity tracks revocation state correctly")
    void authSessionTracksRevocation() {
        UUID userId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(30, ChronoUnit.DAYS);
        AuthSession session = AuthSession.create(userId, expiresAt, "127.0.0.1", "Mozilla/5.0");

        assertThat(session.getUserId()).isEqualTo(userId);
        assertThat(session.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(session.getIpAddress()).isEqualTo("127.0.0.1");
        assertThat(session.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(session.isRevoked()).isFalse();
        assertThat(session.getRevokedAt()).isNull();
        assertThat(session.getRevocationReason()).isNull();
        assertThat(session.isActive()).isTrue();

        Instant revokedAt = Instant.now();
        session.revoke("USER_LOGOUT", revokedAt);

        assertThat(session.isRevoked()).isTrue();
        assertThat(session.getRevokedAt()).isEqualTo(revokedAt);
        assertThat(session.getRevocationReason()).isEqualTo("USER_LOGOUT");
        assertThat(session.isActive()).isFalse();
    }

    @Test
    @DisplayName("AuthSession marks past expiry date as inactive")
    void authSessionExpiredIsInactive() {
        UUID userId = UUID.randomUUID();
        Instant pastExpiry = Instant.now().minus(1, ChronoUnit.MINUTES);
        AuthSession session = AuthSession.create(userId, pastExpiry, "10.0.0.1", "Agent");

        assertThat(session.isRevoked()).isFalse();
        assertThat(session.isExpired()).isTrue();
        assertThat(session.isActive()).isFalse();
    }

    @Test
    @DisplayName("RefreshToken entity manages consumption state")
    void refreshTokenStateTransitions() {
        UUID sessionId = UUID.randomUUID();
        String tokenHash = "a".repeat(64);
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS);

        RefreshToken token = RefreshToken.createInitial(sessionId, tokenHash, expiresAt);
        assertThat(token.getSessionId()).isEqualTo(sessionId);
        assertThat(token.getTokenHash()).isEqualTo(tokenHash);
        assertThat(token.getSequenceNumber()).isEqualTo(1);
        assertThat(token.getExpiresAt()).isEqualTo(expiresAt);
        assertThat(token.isConsumed()).isFalse();
        assertThat(token.getConsumedAt()).isNull();
        assertThat(token.getReplacedByTokenId()).isNull();

        UUID replacementId = UUID.randomUUID();
        Instant consumedAt = Instant.now();
        token.consume(replacementId, consumedAt);

        assertThat(token.isConsumed()).isTrue();
        assertThat(token.getConsumedAt()).isEqualTo(consumedAt);
        assertThat(token.getReplacedByTokenId()).isEqualTo(replacementId);

        assertThatThrownBy(() -> token.consume(UUID.randomUUID(), Instant.now()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already consumed");
    }

    @Test
    @DisplayName("Role enum values and names match security specification")
    void roleEnumValuesMatchSpecification() {
        assertThat(Role.values()).containsExactly(
                Role.ADMIN,
                Role.MANAGER,
                Role.OPERATOR,
                Role.VIEWER
        );
    }

    @Test
    @DisplayName("Organization and OrganizationMembership maintain relations")
    void organizationMembershipInvariants() {
        Organization org = Organization.create("Acme Corp");
        assertThat(org.getName()).isEqualTo("Acme Corp");
        assertThat(org.getId()).isNotNull();

        UUID userId = UUID.randomUUID();
        OrganizationMembership membership = OrganizationMembership.create(org.getId(), userId, Role.ADMIN);

        assertThat(membership.getOrganizationId()).isEqualTo(org.getId());
        assertThat(membership.getUserId()).isEqualTo(userId);
        assertThat(membership.getRole()).isEqualTo(Role.ADMIN);
        assertThat(membership.isActive()).isTrue();

        membership.setActive(false);
        assertThat(membership.isActive()).isFalse();
    }

    @Test
    @DisplayName("LoginCommand toString redacts password while keeping non-sensitive metadata visible")
    void loginCommandToStringRedactsPassword() {
        com.stockguard.security.model.LoginCommand command = new com.stockguard.security.model.LoginCommand(
                "user@example.com",
                "SuperSecretPassword123!",
                "192.168.1.1",
                "Mozilla/5.0"
        );

        String str = command.toString();
        assertThat(str).doesNotContain("SuperSecretPassword123!");
        assertThat(str).contains("password=[PROTECTED]");
        assertThat(str).contains("user@example.com");
        assertThat(str).contains("192.168.1.1");
        assertThat(str).contains("Mozilla/5.0");
    }
}
