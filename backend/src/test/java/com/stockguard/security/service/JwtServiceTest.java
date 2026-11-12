package com.stockguard.security.service;

import com.stockguard.security.config.SecurityProperties;
import com.stockguard.security.exception.JwtValidationException;
import com.stockguard.security.model.ValidatedTokenClaims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String ACTIVE_KEY_ID = "sg-2026-v1";
    private static final String RETIRING_KEY_ID = "sg-2025-v0";
    // 32-byte Base64 test keys:
    private static final String ACTIVE_SECRET_BASE64 = "YWN0aXZlLXNlY3JldC0zMi1ieXRlcy1jcnlwdG8ta2V5LTIwMjY=";
    private static final String RETIRING_SECRET_BASE64 = "cmV0aXJpbmctc2VjcmV0LTMyLWJ5dGVzLWtleS0yMDI2IQ==";

    private static final String ISSUER = "stockguard-platform";
    private static final String AUDIENCE = "stockguard-api";

    private Instant fixedInstant;
    private Clock fixedClock;
    private SecurityProperties properties;
    private JwtService jwtService;
    private SecretKey activeSecretKey;

    @BeforeEach
    void setUp() {
        fixedInstant = Instant.parse("2026-11-12T12:00:00Z");
        fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

        properties = new SecurityProperties();
        properties.getJwt().getActiveKey().setId(ACTIVE_KEY_ID);
        properties.getJwt().getActiveKey().setSecret(ACTIVE_SECRET_BASE64);
        properties.getJwt().setRetiringKeys(Map.of(RETIRING_KEY_ID, RETIRING_SECRET_BASE64));
        properties.getJwt().setIssuer(ISSUER);
        properties.getJwt().setAudience(AUDIENCE);
        properties.getJwt().setAccessTokenExpirationMinutes(15);

        jwtService = new JwtService(properties, fixedClock);
        activeSecretKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(ACTIVE_SECRET_BASE64));
    }

    @Test
    @DisplayName("1. Correct token issuance produces a compact 3-part JWT without org_id or role")
    void correctTokenIssuanceWithoutOrgIdOrRole() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String email = "operator@stockguard.io";

        String token = jwtService.generateAccessToken(userId, email, sessionId);

        assertThat(token).isNotBlank();
        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);

        // Verify payload explicitly contains approved claims and lacks org_id and role
        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        assertThat(payloadJson).contains("\"sub\":\"" + userId + "\"");
        assertThat(payloadJson).contains("\"session_id\":\"" + sessionId + "\"");
        assertThat(payloadJson).contains("\"email\":\"" + email + "\"");
        assertThat(payloadJson).contains("\"jti\":");
        assertThat(payloadJson).contains("\"iss\":\"" + ISSUER + "\"");
        assertThat(payloadJson).contains("\"aud\":[\"" + AUDIENCE + "\"]");
        assertThat(payloadJson).contains("\"iat\":");
        assertThat(payloadJson).contains("\"exp\":");

        // Architectural invariant: organization snapshot claims MUST NOT exist in JWT
        assertThat(payloadJson).doesNotContain("\"org_id\"");
        assertThat(payloadJson).doesNotContain("\"role\"");
    }

    @Test
    @DisplayName("2. Token header specifies HS256 algorithm, typ JWT, and active kid")
    void headerSpecifiesHs256AndActiveKid() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        String token = jwtService.generateAccessToken(userId, "user@example.com", sessionId);

        String headerJson = new String(Base64.getUrlDecoder().decode(token.split("\\.")[0]), StandardCharsets.UTF_8);
        assertThat(headerJson).contains("\"alg\":\"HS256\"");
        assertThat(headerJson).contains("\"kid\":\"" + ACTIVE_KEY_ID + "\"");
        assertThat(headerJson).contains("\"typ\":\"JWT\"");
    }

    @Test
    @DisplayName("3. Token payload contains matching subject, session_id, and normalized email")
    void payloadContainsSubjectSessionIdAndEmail() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String email = "manager@stockguard.io";

        String token = jwtService.generateAccessToken(userId, email, sessionId);
        ValidatedTokenClaims claims = jwtService.parseAndValidateToken(token);

        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.sessionId()).isEqualTo(sessionId);
        assertThat(claims.email()).isEqualTo(email);
    }

    @Test
    @DisplayName("4. Email claim is strictly normalized to trimmed lowercase")
    void emailClaimIsNormalizedToTrimmedLowercase() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String rawEmail = "  Operator.Alpha@StockGuard.IO  ";

        String token = jwtService.generateAccessToken(userId, rawEmail, sessionId);
        ValidatedTokenClaims claims = jwtService.parseAndValidateToken(token);

        assertThat(claims.email()).isEqualTo("operator.alpha@stockguard.io");

        String payloadJson = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(payloadJson).contains("\"email\":\"operator.alpha@stockguard.io\"");
    }

    @Test
    @DisplayName("5. Rejection of tokens missing or with blank email claim")
    void rejectionOfMissingOrBlankEmail() {
        // Missing email claim
        String missingEmailToken = Jwts.builder()
                .header().type("JWT").keyId(ACTIVE_KEY_ID).and()
                .subject(UUID.randomUUID().toString())
                .claim("session_id", UUID.randomUUID().toString())
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(fixedInstant))
                .expiration(Date.from(fixedInstant.plus(Duration.ofMinutes(15))))
                .signWith(activeSecretKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(missingEmailToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Missing required 'email' claim");

        // Blank email claim
        String blankEmailToken = Jwts.builder()
                .header().type("JWT").keyId(ACTIVE_KEY_ID).and()
                .subject(UUID.randomUUID().toString())
                .claim("session_id", UUID.randomUUID().toString())
                .claim("email", "   ")
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(fixedInstant))
                .expiration(Date.from(fixedInstant.plus(Duration.ofMinutes(15))))
                .signWith(activeSecretKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(blankEmailToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Missing required 'email' claim");
    }

    @Test
    @DisplayName("6. JTI is present, non-blank UUID, and unique across tokens issued for identical session")
    void jtiPresenceAndUniquenessAcrossTokens() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String email = "admin@stockguard.io";

        String token1 = jwtService.generateAccessToken(userId, email, sessionId);
        String token2 = jwtService.generateAccessToken(userId, email, sessionId);

        ValidatedTokenClaims claims1 = jwtService.parseAndValidateToken(token1);
        ValidatedTokenClaims claims2 = jwtService.parseAndValidateToken(token2);

        assertThat(claims1.jti()).isNotBlank();
        assertThat(claims2.jti()).isNotBlank();

        // Must be parseable as valid UUIDs
        UUID jti1 = UUID.fromString(claims1.jti());
        UUID jti2 = UUID.fromString(claims2.jti());
        assertThat(jti1).isNotNull();
        assertThat(jti2).isNotNull();

        // Architectural invariant: tokens for same session receive unique JTIs
        assertThat(claims1.jti()).isNotEqualTo(claims2.jti());
    }

    @Test
    @DisplayName("7. Rejection of tokens missing jti claim")
    void rejectionOfMissingJti() {
        String tokenWithoutJti = Jwts.builder()
                .header().type("JWT").keyId(ACTIVE_KEY_ID).and()
                .subject(UUID.randomUUID().toString())
                .claim("session_id", UUID.randomUUID().toString())
                .claim("email", "user@example.com")
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(fixedInstant))
                .expiration(Date.from(fixedInstant.plus(Duration.ofMinutes(15))))
                .signWith(activeSecretKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(tokenWithoutJti))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Missing required 'jti' claim");
    }

    @Test
    @DisplayName("8. Rejection of tokens with blank jti claim")
    void rejectionOfBlankJti() {
        String tokenWithBlankJti = Jwts.builder()
                .header().type("JWT").keyId(ACTIVE_KEY_ID).and()
                .subject(UUID.randomUUID().toString())
                .claim("session_id", UUID.randomUUID().toString())
                .claim("email", "user@example.com")
                .id("   ")
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(fixedInstant))
                .expiration(Date.from(fixedInstant.plus(Duration.ofMinutes(15))))
                .signWith(activeSecretKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(tokenWithBlankJti))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Missing required 'jti' claim");
    }

    @Test
    @DisplayName("9. Rejection of tokens with malformed (non-UUID) jti claim")
    void rejectionOfMalformedJti() {
        String tokenWithMalformedJti = Jwts.builder()
                .header().type("JWT").keyId(ACTIVE_KEY_ID).and()
                .subject(UUID.randomUUID().toString())
                .claim("session_id", UUID.randomUUID().toString())
                .claim("email", "user@example.com")
                .id("not-a-valid-uuid-12345")
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(fixedInstant))
                .expiration(Date.from(fixedInstant.plus(Duration.ofMinutes(15))))
                .signWith(activeSecretKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(tokenWithMalformedJti))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Token identifier ('jti') is not a valid UUID");
    }

    @Test
    @DisplayName("10. Token expiration is enforced to exactly 15 minutes past issuance")
    void tokenExpirationEnforcedTo15Minutes() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        String token = jwtService.generateAccessToken(userId, "user@example.com", sessionId);
        ValidatedTokenClaims claims = jwtService.parseAndValidateToken(token);

        assertThat(claims.issuedAt()).isEqualTo(fixedInstant);
        assertThat(claims.expiresAt()).isEqualTo(fixedInstant.plus(Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("11. Token is successfully verified using the active key")
    void verificationUsingActiveKeySucceeds() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();

        String token = jwtService.generateAccessToken(userId, "test@stockguard.io", sessionId);
        ValidatedTokenClaims claims = jwtService.parseAndValidateToken(token);

        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.sessionId()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("12. Token signed with retiring key is successfully verified via retiring key store")
    void verificationUsingRetiringKeySucceeds() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String email = "retiring@stockguard.io";

        SecretKey retiringKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(RETIRING_SECRET_BASE64));
        String token = Jwts.builder()
                .header()
                    .type("JWT")
                    .keyId(RETIRING_KEY_ID)
                    .and()
                .subject(userId.toString())
                .claim("session_id", sessionId.toString())
                .claim("email", email)
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(fixedInstant))
                .expiration(Date.from(fixedInstant.plus(Duration.ofMinutes(15))))
                .signWith(retiringKey, Jwts.SIG.HS256)
                .compact();

        ValidatedTokenClaims claims = jwtService.parseAndValidateToken(token);
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.sessionId()).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("13. Rejection of unknown kid")
    void rejectionOfUnknownKid() {
        SecretKey foreignKey = Keys.hmacShaKeyFor(Base64.getDecoder().decode(ACTIVE_SECRET_BASE64));
        String token = Jwts.builder()
                .header()
                    .type("JWT")
                    .keyId("unknown-key-999")
                    .and()
                .subject(UUID.randomUUID().toString())
                .claim("session_id", UUID.randomUUID().toString())
                .claim("email", "hacker@evil.com")
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(fixedInstant))
                .expiration(Date.from(fixedInstant.plus(Duration.ofMinutes(15))))
                .signWith(foreignKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Unknown or untrusted 'kid'");
    }

    @Test
    @DisplayName("14. Rejection of missing kid in header")
    void rejectionOfMissingKid() {
        String token = Jwts.builder()
                .header()
                    .type("JWT")
                    // Omit kid
                    .and()
                .subject(UUID.randomUUID().toString())
                .claim("session_id", UUID.randomUUID().toString())
                .claim("email", "nokid@example.com")
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(fixedInstant))
                .expiration(Date.from(fixedInstant.plus(Duration.ofMinutes(15))))
                .signWith(activeSecretKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("missing required 'kid'");
    }

    @Test
    @DisplayName("15. Cryptographic signature verification happens before claims are trusted")
    void rejectionOfModifiedSignatureBeforeClaimsTrusted() {
        String token = jwtService.generateAccessToken(UUID.randomUUID(), "user@example.com", UUID.randomUUID());
        String[] parts = token.split("\\.");
        // Corrupt signature segment
        String tamperedToken = parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 4) + "XXXX";

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(tamperedToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("JWT verification failed");
    }

    @Test
    @DisplayName("16. Rejection of expired token")
    void rejectionOfExpiredToken() {
        // Fast-forward clock past 15-minute expiration
        Clock advanceClock = Clock.fixed(fixedInstant.plus(Duration.ofMinutes(16)), ZoneOffset.UTC);
        JwtService lateService = new JwtService(properties, advanceClock);

        String token = jwtService.generateAccessToken(UUID.randomUUID(), "user@example.com", UUID.randomUUID());

        assertThatThrownBy(() -> lateService.parseAndValidateToken(token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("JWT token has expired");
    }

    @Test
    @DisplayName("17. Rejection of invalid issuer or audience")
    void rejectionOfInvalidIssuerOrAudience() {
        // Wrong Issuer
        String badIssuerToken = Jwts.builder()
                .header().type("JWT").keyId(ACTIVE_KEY_ID).and()
                .subject(UUID.randomUUID().toString())
                .claim("session_id", UUID.randomUUID().toString())
                .claim("email", "bad@example.com")
                .id(UUID.randomUUID().toString())
                .issuer("untrusted-issuer")
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(fixedInstant))
                .expiration(Date.from(fixedInstant.plus(Duration.ofMinutes(15))))
                .signWith(activeSecretKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(badIssuerToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("JWT verification failed");

        // Wrong Audience
        String badAudienceToken = Jwts.builder()
                .header().type("JWT").keyId(ACTIVE_KEY_ID).and()
                .subject(UUID.randomUUID().toString())
                .claim("session_id", UUID.randomUUID().toString())
                .claim("email", "bad@example.com")
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add("untrusted-audience").and()
                .issuedAt(Date.from(fixedInstant))
                .expiration(Date.from(fixedInstant.plus(Duration.ofMinutes(15))))
                .signWith(activeSecretKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(badAudienceToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("JWT verification failed");
    }

    @Test
    @DisplayName("18. Rejection of malformed subject and session_id UUID claims")
    void rejectionOfMalformedSubjectAndSessionId() {
        // Non-UUID subject
        String badSubToken = Jwts.builder()
                .header().type("JWT").keyId(ACTIVE_KEY_ID).and()
                .subject("not-a-valid-uuid")
                .claim("session_id", UUID.randomUUID().toString())
                .claim("email", "user@example.com")
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(fixedInstant))
                .expiration(Date.from(fixedInstant.plus(Duration.ofMinutes(15))))
                .signWith(activeSecretKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(badSubToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Subject claim is not a valid UUID");

        // Non-UUID session_id
        String badSessionToken = Jwts.builder()
                .header().type("JWT").keyId(ACTIVE_KEY_ID).and()
                .subject(UUID.randomUUID().toString())
                .claim("session_id", "invalid-session-id")
                .claim("email", "user@example.com")
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(fixedInstant))
                .expiration(Date.from(fixedInstant.plus(Duration.ofMinutes(15))))
                .signWith(activeSecretKey, Jwts.SIG.HS256)
                .compact();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(badSessionToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Session ID claim is not a valid UUID");
    }

    @Test
    @DisplayName("19. Strict algorithm pinning: rejection of none / unsigned tokens")
    void rejectionOfNoneAndUnsignedTokens() {
        // 2-part unsigned token
        String unsignedToken = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes(StandardCharsets.UTF_8))
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"d3b07384-d113-462c-a521-729906d34199\"}".getBytes(StandardCharsets.UTF_8))
                + ".";

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(unsignedToken))
                .isInstanceOf(JwtValidationException.class);

        // 3-part token claiming alg: none with active kid
        String forgedNoneToken = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"alg\":\"none\",\"typ\":\"JWT\",\"kid\":\"" + ACTIVE_KEY_ID + "\"}").getBytes(StandardCharsets.UTF_8))
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"sub\":\"" + UUID.randomUUID() + "\",\"session_id\":\"" + UUID.randomUUID() + "\",\"email\":\"user@example.com\"}").getBytes(StandardCharsets.UTF_8))
                + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString("dummy-signature".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(forgedNoneToken))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("disallowed");
    }

    @Test
    @DisplayName("20. Strict algorithm pinning: rejection of asymmetric algorithms (RS256)")
    void rejectionOfAsymmetricAlgorithmRs256() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        String rs256Token = Jwts.builder()
                .header()
                    .type("JWT")
                    .keyId(ACTIVE_KEY_ID)
                    .and()
                .subject(UUID.randomUUID().toString())
                .claim("session_id", UUID.randomUUID().toString())
                .claim("email", "user@example.com")
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(fixedInstant))
                .expiration(Date.from(fixedInstant.plus(Duration.ofMinutes(15))))
                .signWith(kp.getPrivate(), Jwts.SIG.RS256)
                .compact();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(rs256Token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Unsupported algorithm: 'RS256'");
    }

    @Test
    @DisplayName("21. Strict algorithm pinning: rejection of unapproved HMAC algorithms (HS384, HS512)")
    void rejectionOfOtherHmacAlgorithms() {
        // HS384 token
        SecretKey key384 = Keys.hmacShaKeyFor(new byte[48]);
        String hs384Token = Jwts.builder()
                .header()
                    .type("JWT")
                    .keyId(ACTIVE_KEY_ID)
                    .and()
                .subject(UUID.randomUUID().toString())
                .claim("session_id", UUID.randomUUID().toString())
                .claim("email", "user@example.com")
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(fixedInstant))
                .expiration(Date.from(fixedInstant.plus(Duration.ofMinutes(15))))
                .signWith(key384, Jwts.SIG.HS384)
                .compact();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(hs384Token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Unsupported algorithm: 'HS384'");

        // HS512 token
        SecretKey key512 = Keys.hmacShaKeyFor(new byte[64]);
        String hs512Token = Jwts.builder()
                .header()
                    .type("JWT")
                    .keyId(ACTIVE_KEY_ID)
                    .and()
                .subject(UUID.randomUUID().toString())
                .claim("session_id", UUID.randomUUID().toString())
                .claim("email", "user@example.com")
                .id(UUID.randomUUID().toString())
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .issuedAt(Date.from(fixedInstant))
                .expiration(Date.from(fixedInstant.plus(Duration.ofMinutes(15))))
                .signWith(key512, Jwts.SIG.HS512)
                .compact();

        assertThatThrownBy(() -> jwtService.parseAndValidateToken(hs512Token))
                .isInstanceOf(JwtValidationException.class)
                .hasMessageContaining("Unsupported algorithm: 'HS512'");
    }
}
