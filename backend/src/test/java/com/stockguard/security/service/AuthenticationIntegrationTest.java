package com.stockguard.security.service;

import com.stockguard.security.crypto.TokenCryptoUtils;
import com.stockguard.security.domain.AuthSession;
import com.stockguard.security.domain.RefreshToken;
import com.stockguard.security.domain.User;
import com.stockguard.security.exception.*;
import com.stockguard.security.model.LoginCommand;
import com.stockguard.security.model.LoginResult;
import com.stockguard.security.model.RefreshResult;
import com.stockguard.security.model.ValidatedTokenClaims;
import com.stockguard.security.repository.AuthSessionRepository;
import com.stockguard.security.repository.RefreshTokenRepository;
import com.stockguard.security.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("dev")
class AuthenticationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("stockguard_test")
            .withUsername("test_user")
            .withPassword("test_pass")
            .withInitScript("init-test-roles.sql");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "stockguard_app");
        registry.add("spring.datasource.password", () -> "test_app_pass");
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", () -> "stockguard_owner");
        registry.add("spring.flyway.password", () -> "test_owner_pass");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("stockguard.security.bootstrap.enabled", () -> "false");
    }

    @Autowired
    private AuthenticationService authenticationService;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    private JdbcTemplate privilegedJdbcTemplate;

    @BeforeEach
    void setUp() {
        var privDataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        this.privilegedJdbcTemplate = new JdbcTemplate(privDataSource);
    }

    private User createTestUser(String email, String rawPassword, boolean active) {
        String encodedPassword = passwordEncoder.encode(rawPassword);
        User user = User.create(email, encodedPassword);
        user.setActive(active);
        return userRepository.save(user);
    }

    @Test
    @DisplayName("login with valid credentials issues valid tokens and database session")
    void loginValidCredentialsSuccess() {
        String email = "alice@example.com";
        String password = "StrongPassword123!";
        User user = createTestUser(email, password, true);

        LoginResult result = authenticationService.login(
                new LoginCommand(email, password, "192.168.1.100", "IntegrationTestClient/1.0")
        );

        assertThat(result).isNotNull();
        assertThat(result.sessionId()).isNotNull();
        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.rawRefreshToken()).isNotBlank();
        assertThat(result.rawRefreshToken()).hasSize(43);

        // Verify JWT claims
        ValidatedTokenClaims claims = jwtService.parseAndValidateToken(result.accessToken());
        assertThat(claims.userId()).isEqualTo(user.getId());
        assertThat(claims.sessionId()).isEqualTo(result.sessionId());
        assertThat(claims.email()).isEqualTo(user.getNormalizedEmail());

        // Verify AuthSession in DB
        AuthSession session = authSessionRepository.findById(result.sessionId()).orElseThrow();
        assertThat(session.getUserId()).isEqualTo(user.getId());
        assertThat(session.getIpAddress()).isEqualTo("192.168.1.100");
        assertThat(session.getUserAgent()).isEqualTo("IntegrationTestClient/1.0");
        assertThat(session.isRevoked()).isFalse();

        // Verify RefreshToken in DB
        String expectedHash = TokenCryptoUtils.hashToken(result.rawRefreshToken());
        RefreshToken token = refreshTokenRepository.findByTokenHash(expectedHash).orElseThrow();
        assertThat(token.getSessionId()).isEqualTo(result.sessionId());
        assertThat(token.getSequenceNumber()).isEqualTo(1);
        assertThat(token.isConsumed()).isFalse();
    }

    @Test
    @DisplayName("login normalizes email for lookup")
    void loginNormalizesEmail() {
        String rawPassword = "CorrectPassword123!";
        createTestUser("bob.normalized@example.com", rawPassword, true);

        LoginResult result = authenticationService.login(
                new LoginCommand("  BOB.NORMALIZED@EXAMPLE.COM  ", rawPassword, "10.0.0.1", "Agent")
        );

        assertThat(result).isNotNull();
        assertThat(result.sessionId()).isNotNull();
    }

    @Test
    @DisplayName("login with incorrect password throws AuthenticationFailedException")
    void loginIncorrectPasswordThrows() {
        String email = "carol@example.com";
        createTestUser(email, "Secret123!", true);

        assertThatThrownBy(() -> authenticationService.login(
                new LoginCommand(email, "WrongPassword!", "10.0.0.1", "Agent")
        )).isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    @DisplayName("login with non-existent user throws AuthenticationFailedException")
    void loginNonExistentUserThrows() {
        assertThatThrownBy(() -> authenticationService.login(
                new LoginCommand("nonexistent@example.com", "Secret123!", "10.0.0.1", "Agent")
        )).isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    @DisplayName("login with inactive user throws AuthenticationFailedException")
    void loginInactiveUserThrows() {
        String email = "inactive@example.com";
        createTestUser(email, "Secret123!", false);

        assertThatThrownBy(() -> authenticationService.login(
                new LoginCommand(email, "Secret123!", "10.0.0.1", "Agent")
        )).isInstanceOf(AuthenticationFailedException.class);
    }

    @Test
    @DisplayName("refresh rotates active token into successor and consumes predecessor")
    void refreshSuccess() {
        String email = "rotation@example.com";
        String password = "Password123!";
        User user = createTestUser(email, password, true);

        LoginResult login = authenticationService.login(
                new LoginCommand(email, password, "10.0.0.1", "Client")
        );

        RefreshResult refresh = authenticationService.refresh(login.rawRefreshToken());

        assertThat(refresh).isNotNull();
        assertThat(refresh.sessionId()).isEqualTo(login.sessionId());
        assertThat(refresh.accessToken()).isNotBlank();
        assertThat(refresh.rawRefreshToken()).isNotBlank();
        assertThat(refresh.rawRefreshToken()).isNotEqualTo(login.rawRefreshToken());

        // Predecessor must be consumed
        String predHash = TokenCryptoUtils.hashToken(login.rawRefreshToken());
        RefreshToken pred = refreshTokenRepository.findByTokenHash(predHash).orElseThrow();
        assertThat(pred.isConsumed()).isTrue();
        assertThat(pred.getReplacedByTokenId()).isNotNull();

        // Successor must be active with sequence number 2
        String succHash = TokenCryptoUtils.hashToken(refresh.rawRefreshToken());
        RefreshToken succ = refreshTokenRepository.findByTokenHash(succHash).orElseThrow();
        assertThat(succ.getId()).isEqualTo(pred.getReplacedByTokenId());
        assertThat(succ.getSequenceNumber()).isEqualTo(2);
        assertThat(succ.isConsumed()).isFalse();

        // New access token must be valid
        ValidatedTokenClaims claims = jwtService.parseAndValidateToken(refresh.accessToken());
        assertThat(claims.userId()).isEqualTo(user.getId());
        assertThat(claims.sessionId()).isEqualTo(login.sessionId());
    }

    @Test
    @DisplayName("replay detection immediately revokes session family and blocks subsequent refreshes")
    void replayDetectionRevokesSessionFamily() {
        String email = "replay@example.com";
        String password = "Password123!";
        createTestUser(email, password, true);

        LoginResult login = authenticationService.login(
                new LoginCommand(email, password, "10.0.0.1", "Client")
        );

        // Legitimate rotation
        RefreshResult legitimateRotation = authenticationService.refresh(login.rawRefreshToken());

        // Attacker replays consumed token
        assertThatThrownBy(() -> authenticationService.refresh(login.rawRefreshToken()))
                .isInstanceOf(TokenReuseException.class);

        // Verify session is revoked in DB
        AuthSession session = authSessionRepository.findById(login.sessionId()).orElseThrow();
        assertThat(session.isRevoked()).isTrue();
        assertThat(session.getRevocationReason()).isEqualTo("TOKEN_REUSE_DETECTED");

        // Subsequent rotation with legitimate successor must now also fail
        assertThatThrownBy(() -> authenticationService.refresh(legitimateRotation.rawRefreshToken()))
                .isInstanceOf(SessionRevokedException.class);
    }

    @Test
    @DisplayName("concurrent rotation race on same token allows at most one success")
    void concurrentRotationRace() throws Exception {
        String email = "concurrent@example.com";
        String password = "Password123!";
        createTestUser(email, password, true);

        LoginResult login = authenticationService.login(
                new LoginCommand(email, password, "10.0.0.1", "Client")
        );

        int concurrency = 4;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CyclicBarrier barrier = new CyclicBarrier(concurrency);
        List<Future<RefreshResult>> futures = new ArrayList<>();

        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> {
                barrier.await();
                return authenticationService.refresh(login.rawRefreshToken());
            }));
        }

        int successCount = 0;
        int failureCount = 0;

        for (Future<RefreshResult> f : futures) {
            try {
                RefreshResult res = f.get();
                if (res != null) {
                    successCount++;
                }
            } catch (ExecutionException e) {
                failureCount++;
                assertThat(e.getCause()).isInstanceOfAny(
                        TokenReuseException.class,
                        SessionRevokedException.class,
                        org.springframework.dao.DataAccessException.class
                );
            }
        }

        executor.shutdown();

        // At most 1 thread can ever succeed
        assertThat(successCount).isLessThanOrEqualTo(1);
        assertThat(failureCount).isGreaterThanOrEqualTo(concurrency - 1);

        // Identify winning successor token (if one thread succeeded before replay was committed)
        RefreshResult winningResult = null;
        for (Future<RefreshResult> f : futures) {
            try {
                RefreshResult res = f.get();
                if (res != null) {
                    winningResult = res;
                }
            } catch (ExecutionException ignored) {
            }
        }

        // The session family must be permanently revoked due to replay detection by the racing threads
        AuthSession session = authSessionRepository.findById(login.sessionId()).orElseThrow();
        assertThat(session.isRevoked()).isTrue();
        assertThat(session.getRevocationReason()).isEqualTo("TOKEN_REUSE_DETECTED");

        // The winning successor token MUST be unusable because the session family has been revoked
        if (winningResult != null) {
            final RefreshResult winner = winningResult;
            assertThatThrownBy(() -> authenticationService.refresh(winner.rawRefreshToken()))
                    .isInstanceOf(SessionRevokedException.class);
        }
    }

    @Test
    @DisplayName("replay revocation persists independently when invoked within an outer transaction that rolls back")
    void replayRevocationPersistsWhenOuterTransactionRollsBack() {
        String email = "outer-tx-rollback@example.com";
        String password = "Password123!";
        createTestUser(email, password, true);

        LoginResult login = authenticationService.login(
                new LoginCommand(email, password, "10.0.0.1", "Client")
        );

        // Legitimate first rotation consumes initial token
        RefreshResult legitimateRotation = authenticationService.refresh(login.rawRefreshToken());
        assertThat(legitimateRotation).isNotNull();

        // Start an outer transaction
        org.springframework.transaction.support.DefaultTransactionDefinition outerDef =
                new org.springframework.transaction.support.DefaultTransactionDefinition();
        outerDef.setName("outerTestTransaction");
        org.springframework.transaction.TransactionStatus outerStatus = transactionManager.getTransaction(outerDef);

        try {
            // Replay presentation inside the outer transaction
            assertThatThrownBy(() -> authenticationService.refresh(login.rawRefreshToken()))
                    .isInstanceOf(TokenReuseException.class);
        } finally {
            // Explicitly roll back the outer transaction
            transactionManager.rollback(outerStatus);
        }

        // Verify that the session family remains permanently revoked in the database despite the outer rollback
        AuthSession session = authSessionRepository.findById(login.sessionId()).orElseThrow();
        assertThat(session.isRevoked()).isTrue();
        assertThat(session.getRevocationReason()).isEqualTo("TOKEN_REUSE_DETECTED");

        // Subsequent rotation with legitimate successor must fail with SessionRevokedException
        assertThatThrownBy(() -> authenticationService.refresh(legitimateRotation.rawRefreshToken()))
                .isInstanceOf(SessionRevokedException.class);
    }

    @Test
    @DisplayName("refresh rejects expired refresh token")
    void refreshExpiredToken() {
        String email = "expiredtoken@example.com";
        String password = "Password123!";
        User user = createTestUser(email, password, true);

        // Create an independent session with a single active token that has expired
        UUID expiredSessionId = UUID.randomUUID();
        Instant now = Instant.now();
        Instant createdAt = now.minus(10, ChronoUnit.DAYS);
        Instant tokenExpiresAt = now.minus(3, ChronoUnit.DAYS);
        Instant sessionExpiresAt = now.plus(20, ChronoUnit.DAYS);

        privilegedJdbcTemplate.update(
                "INSERT INTO auth_sessions (id, user_id, user_agent, ip_address, revoked, expires_at, created_at, updated_at) " +
                "VALUES (?, ?, 'Client', '10.0.0.1', FALSE, ?, ?, ?)",
                expiredSessionId,
                user.getId(),
                Timestamp.from(sessionExpiresAt),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)
        );

        String rawExpiredToken = TokenCryptoUtils.generateOpaqueToken();
        String tokenHash = TokenCryptoUtils.hashToken(rawExpiredToken);
        UUID expiredTokenId = UUID.randomUUID();

        privilegedJdbcTemplate.update(
                "INSERT INTO refresh_tokens (id, session_id, token_hash, sequence_number, expires_at, created_at) " +
                "VALUES (?, ?, ?, 1, ?, ?)",
                expiredTokenId,
                expiredSessionId,
                tokenHash,
                Timestamp.from(tokenExpiresAt),
                Timestamp.from(createdAt)
        );

        assertThatThrownBy(() -> authenticationService.refresh(rawExpiredToken))
                .isInstanceOf(TokenExpiredException.class);
    }

    @Test
    @DisplayName("refresh rejects expired session")
    void refreshExpiredSession() {
        String email = "expiredsession@example.com";
        String password = "Password123!";
        createTestUser(email, password, true);

        LoginResult login = authenticationService.login(
                new LoginCommand(email, password, "10.0.0.1", "Client")
        );

        // Update session created_at and expires_at maintaining check constraint (expires_at > created_at)
        Instant now = Instant.now();
        Instant createdAt = now.minus(35, ChronoUnit.DAYS);
        Instant expiresAt = now.minus(5, ChronoUnit.DAYS);

        privilegedJdbcTemplate.update(
                "UPDATE auth_sessions SET created_at = ?, expires_at = ? WHERE id = ?",
                Timestamp.from(createdAt),
                Timestamp.from(expiresAt),
                login.sessionId()
        );

        assertThatThrownBy(() -> authenticationService.refresh(login.rawRefreshToken()))
                .isInstanceOf(SessionExpiredException.class);
    }

    @Test
    @DisplayName("logout revokes session and subsequent refreshes fail")
    void logoutRevokesSession() {
        String email = "logout@example.com";
        String password = "Password123!";
        createTestUser(email, password, true);

        LoginResult login = authenticationService.login(
                new LoginCommand(email, password, "10.0.0.1", "Client")
        );

        authenticationService.logout(login.rawRefreshToken());

        AuthSession session = authSessionRepository.findById(login.sessionId()).orElseThrow();
        assertThat(session.isRevoked()).isTrue();
        assertThat(session.getRevocationReason()).isEqualTo("LOGOUT");

        assertThatThrownBy(() -> authenticationService.refresh(login.rawRefreshToken()))
                .isInstanceOf(SessionRevokedException.class);
    }
}
