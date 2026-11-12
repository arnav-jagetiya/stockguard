package com.stockguard.security.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenCryptoUtilsTest {

    @Test
    @DisplayName("generateOpaqueToken returns 256-bit URL-safe token without padding")
    void generateOpaqueTokenReturnsValidFormat() {
        String token = TokenCryptoUtils.generateOpaqueToken();

        assertThat(token).isNotBlank();
        // 32 bytes encoded in Base64URL without padding is exactly 43 characters
        assertThat(token).hasSize(43);
        assertThat(token).matches("^[A-Za-z0-9_-]+$");
    }

    @Test
    @DisplayName("generateOpaqueToken produces unique tokens across iterations")
    void generateOpaqueTokenProducesUniqueTokens() {
        Set<String> tokens = new HashSet<>();
        int count = 1000;

        for (int i = 0; i < count; i++) {
            tokens.add(TokenCryptoUtils.generateOpaqueToken());
        }

        assertThat(tokens).hasSize(count);
    }

    @Test
    @DisplayName("hashToken produces valid lowercase 64-character SHA-256 hex string")
    void hashTokenProducesValidHex() {
        String token = TokenCryptoUtils.generateOpaqueToken();
        String hash = TokenCryptoUtils.hashToken(token);

        assertThat(hash).isNotBlank();
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("hashToken is deterministic for identical input")
    void hashTokenIsDeterministic() {
        String token = "sample-refresh-token-for-testing-12345";
        String hash1 = TokenCryptoUtils.hashToken(token);
        String hash2 = TokenCryptoUtils.hashToken(token);

        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("hashToken matches standard SHA-256 test vector")
    void hashTokenMatchesStandardTestVector() {
        // echo -n "hello" | sha256sum -> 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        String hash = TokenCryptoUtils.hashToken("hello");
        assertThat(hash).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    @DisplayName("hashToken rejects null or empty tokens")
    void hashTokenRejectsNullOrBlank() {
        assertThatThrownBy(() -> TokenCryptoUtils.hashToken(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");

        assertThatThrownBy(() -> TokenCryptoUtils.hashToken(""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");

        assertThatThrownBy(() -> TokenCryptoUtils.hashToken("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }
}
