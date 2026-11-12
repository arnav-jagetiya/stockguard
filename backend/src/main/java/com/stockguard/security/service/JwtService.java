package com.stockguard.security.service;

import com.stockguard.security.config.SecurityProperties;
import com.stockguard.security.exception.JwtValidationException;
import com.stockguard.security.model.ValidatedTokenClaims;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Header;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Locator;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.security.Key;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * High-performance, stateless JWT service for HS256 access token issuance and cryptographic verification.
 * <p>
 * Implements strict algorithm pinning (HS256 only), {@code kid}-based key selection supporting
 * zero-downtime key rotation, issuer/audience validation, and strict claim integrity per ADR-010.
 */
@Service
public class JwtService {

    private static final String ALGORITHM_HEADER_VALUE = "HS256";
    private static final String TYPE_HEADER_VALUE = "JWT";
    private static final String SESSION_ID_CLAIM = "session_id";
    private static final String EMAIL_CLAIM = "email";

    private final SecurityProperties properties;
    private final Clock clock;
    private final SecretKey activeSecretKey;
    private final String activeKeyId;
    private final Map<String, SecretKey> verificationKeyStore;

    @Autowired
    public JwtService(SecurityProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public JwtService(SecurityProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;

        SecurityProperties.Jwt jwt = properties.getJwt();
        if (jwt == null || jwt.getActiveKey() == null) {
            throw new IllegalStateException("JWT configuration and active key must be defined.");
        }

        this.activeKeyId = jwt.getActiveKey().getId();
        this.activeSecretKey = decodeSecretKey(jwt.getActiveKey().getSecret());

        // Initialize verification key store (active key + retiring keys)
        Map<String, SecretKey> keyMap = new HashMap<>();
        keyMap.put(this.activeKeyId, this.activeSecretKey);

        if (jwt.getRetiringKeys() != null) {
            for (Map.Entry<String, String> entry : jwt.getRetiringKeys().entrySet()) {
                keyMap.put(entry.getKey(), decodeSecretKey(entry.getValue()));
            }
        }
        this.verificationKeyStore = Map.copyOf(keyMap);
    }

    /**
     * Generates a signed HS256 access JWT for an authenticated user and session.
     *
     * @param userId    Global user UUID
     * @param email     Normalized email
     * @param sessionId Active session UUID in auth_sessions
     * @return Compact serialized JWT
     */
    public String generateAccessToken(UUID userId, String email, UUID sessionId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId must not be null");
        }
        if (sessionId == null) {
            throw new IllegalArgumentException("sessionId must not be null");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email must not be blank");
        }

        Instant now = clock.instant();
        int expirationMinutes = properties.getJwt().getAccessTokenExpirationMinutes();
        Instant expiresAt = now.plus(Duration.ofMinutes(expirationMinutes));

        return Jwts.builder()
                .header()
                    .type(TYPE_HEADER_VALUE)
                    .keyId(activeKeyId)
                    .and()
                .subject(userId.toString())
                .claim(SESSION_ID_CLAIM, sessionId.toString())
                .claim(EMAIL_CLAIM, email.trim().toLowerCase())
                .id(UUID.randomUUID().toString())
                .issuer(properties.getJwt().getIssuer())
                .audience().add(properties.getJwt().getAudience()).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(activeSecretKey, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Cryptographically verifies an access JWT and extracts validated claims.
     *
     * @param token Compact serialized JWT
     * @return ValidatedTokenClaims domain object
     * @throws JwtValidationException if signature, structure, timing, or claims are invalid.
     */
    public ValidatedTokenClaims parseAndValidateToken(String token) {
        if (token == null || token.isBlank()) {
            throw new JwtValidationException("JWT token string must not be blank.");
        }

        Locator<Key> keyLocator = header -> {
            String alg = header.getAlgorithm();
            if (alg == null || !ALGORITHM_HEADER_VALUE.equals(alg)) {
                throw new JwtValidationException("Unsupported algorithm: '" + alg + "'. Only HS256 is permitted.");
            }

            Object kidObj = header.get("kid");
            if (kidObj == null || kidObj.toString().isBlank()) {
                throw new JwtValidationException("JWT header missing required 'kid' key ID.");
            }

            String kid = kidObj.toString();
            SecretKey secretKey = verificationKeyStore.get(kid);
            if (secretKey == null) {
                throw new JwtValidationException("Unknown or untrusted 'kid': " + kid);
            }
            return secretKey;
        };

        Claims claims;
        try {
            claims = Jwts.parser()
                    .clock(() -> Date.from(clock.instant()))
                    .clockSkewSeconds(0) // Zero clock skew allowance
                    .requireIssuer(properties.getJwt().getIssuer())
                    .requireAudience(properties.getJwt().getAudience())
                    .keyLocator(keyLocator)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException ex) {
            throw new JwtValidationException("JWT token has expired.", ex);
        } catch (JwtException ex) {
            throw new JwtValidationException("JWT verification failed: " + ex.getMessage(), ex);
        }

        return extractAndValidateClaims(claims);
    }

    private ValidatedTokenClaims extractAndValidateClaims(Claims claims) {
        // 1. Subject (userId)
        String sub = claims.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new JwtValidationException("Missing required subject claim ('sub').");
        }
        UUID userId;
        try {
            userId = UUID.fromString(sub);
        } catch (IllegalArgumentException ex) {
            throw new JwtValidationException("Subject claim is not a valid UUID: " + sub);
        }

        // 2. Session ID
        Object sessionIdObj = claims.get(SESSION_ID_CLAIM);
        if (sessionIdObj == null || sessionIdObj.toString().isBlank()) {
            throw new JwtValidationException("Missing required '" + SESSION_ID_CLAIM + "' claim.");
        }
        UUID sessionId;
        try {
            sessionId = UUID.fromString(sessionIdObj.toString());
        } catch (IllegalArgumentException ex) {
            throw new JwtValidationException("Session ID claim is not a valid UUID: " + sessionIdObj);
        }

        // 3. Email
        Object emailObj = claims.get(EMAIL_CLAIM);
        if (emailObj == null || emailObj.toString().isBlank()) {
            throw new JwtValidationException("Missing required '" + EMAIL_CLAIM + "' claim.");
        }
        String email = emailObj.toString();

        // 4. JTI
        String jti = claims.getId();
        if (jti == null || jti.isBlank()) {
            throw new JwtValidationException("Missing required 'jti' claim.");
        }
        try {
            UUID.fromString(jti.trim());
        } catch (IllegalArgumentException ex) {
            throw new JwtValidationException("Token identifier ('jti') is not a valid UUID: " + jti, ex);
        }

        // 5. Timestamps
        Date iat = claims.getIssuedAt();
        if (iat == null) {
            throw new JwtValidationException("Missing required 'iat' claim.");
        }
        Date exp = claims.getExpiration();
        if (exp == null) {
            throw new JwtValidationException("Missing required 'exp' claim.");
        }

        return new ValidatedTokenClaims(
                userId,
                sessionId,
                email,
                jti,
                iat.toInstant(),
                exp.toInstant()
        );
    }

    private static SecretKey decodeSecretKey(String base64Secret) {
        if (base64Secret == null || base64Secret.isBlank()) {
            throw new IllegalStateException("Secret key must not be blank.");
        }
        byte[] keyBytes = Base64.getDecoder().decode(base64Secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
