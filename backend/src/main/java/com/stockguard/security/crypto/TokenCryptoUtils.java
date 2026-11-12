package com.stockguard.security.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * High-entropy cryptographic token generator and SHA-256 hashing utility.
 * <p>
 * Refresh tokens are generated as 256-bit cryptographically secure random values (Base64URL encoded)
 * and persisted exclusively as 64-character lowercase SHA-256 hexadecimal digests.
 */
public final class TokenCryptoUtils {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final HexFormat HEX_FORMAT = HexFormat.of();

    private TokenCryptoUtils() {
        // Utility class
    }

    /**
     * Generates an opaque, cryptographically random refresh token with 256 bits of entropy.
     *
     * @return 43-character Base64URL-encoded random string
     */
    public static String generateOpaqueToken() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return URL_ENCODER.encodeToString(randomBytes);
    }

    /**
     * Computes the SHA-256 hash of a raw token string.
     *
     * @param rawToken The raw refresh token string
     * @return 64-character lowercase hexadecimal hash string
     */
    public static String hashToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new IllegalArgumentException("Token string must not be blank");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HEX_FORMAT.formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    /**
     * Validates whether a token hash conforms to the required 64-character lowercase hexadecimal format.
     */
    public static boolean isValidHashFormat(String hash) {
        return hash != null && hash.matches("^[0-9a-f]{64}$");
    }
}
