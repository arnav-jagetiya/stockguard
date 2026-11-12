package com.stockguard.security.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Validates cryptographic configuration and signing keys on application startup.
 * <p>
 * Ensures HS256 HMAC keys meet minimum 256-bit entropy requirements, key IDs are unique,
 * and insecure placeholder keys are rejected outside development/testing profiles.
 */
@Component
public class SecurityKeyValidator implements ApplicationRunner, InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(SecurityKeyValidator.class);
    private static final int MIN_KEY_BYTES = 32; // 256 bits

    private static final Set<String> KNOWN_PLACEHOLDER_SECRETS = Set.of(
            "vWvE+H3M7P+T0GfQx4zL+cK9p9V5hW8J2eT4rY6uI0A=",
            "dGVzdC1zZWNyZXQtMzItYnl0ZXMtY3J5cHRvLWtleS0yMDI2IQ==",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "dGVzdC1zZWNyZXQtZG8tbm90LXVzZS1pbi1wcm9kdWN0aW9u",
            "Y2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWUtY2hhbmdlbWU="
    );

    private final SecurityProperties securityProperties;
    private final Environment environment;

    public SecurityKeyValidator(SecurityProperties securityProperties, Environment environment) {
        this.securityProperties = securityProperties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        validateSecurityConfiguration();
    }

    @Override
    public void run(ApplicationArguments args) {
        // Already validated during container initialization in afterPropertiesSet()
    }

    /**
     * Executes strict validation of the JWT cryptographic keys and metadata.
     *
     * @throws IllegalStateException if any security invariant is violated.
     */
    public void validateSecurityConfiguration() {
        SecurityProperties.Jwt jwt = securityProperties.getJwt();
        if (jwt == null) {
            throw new IllegalStateException("JWT security configuration is missing.");
        }

        // 1. Validate Issuer and Audience
        if (jwt.getIssuer() == null || jwt.getIssuer().isBlank()) {
            throw new IllegalStateException("JWT issuer (stockguard.security.jwt.issuer) must not be blank.");
        }
        if (jwt.getAudience() == null || jwt.getAudience().isBlank()) {
            throw new IllegalStateException("JWT audience (stockguard.security.jwt.audience) must not be blank.");
        }

        // 2. Validate Active Key
        SecurityProperties.Jwt.ActiveKey activeKey = jwt.getActiveKey();
        if (activeKey == null) {
            throw new IllegalStateException("Active JWT signing key configuration is missing.");
        }
        String activeKeyId = activeKey.getId();
        if (activeKeyId == null || activeKeyId.isBlank()) {
            throw new IllegalStateException("Active JWT key ID (stockguard.security.jwt.active-key.id) must not be blank.");
        }

        byte[] activeSecretBytes = validateBase64Secret(activeKey.getSecret(), "active key [" + activeKeyId + "]");
        validateKeyEntropy(activeSecretBytes, "active key [" + activeKeyId + "]");
        validateProductionSecret(activeKey.getSecret(), "active key [" + activeKeyId + "]");

        // 3. Validate Retiring Keys
        Map<String, String> retiringKeys = jwt.getRetiringKeys();
        if (retiringKeys != null && !retiringKeys.isEmpty()) {
            for (Map.Entry<String, String> entry : retiringKeys.entrySet()) {
                String retiringKeyId = entry.getKey();
                String retiringSecret = entry.getValue();

                if (retiringKeyId == null || retiringKeyId.isBlank()) {
                    throw new IllegalStateException("Retiring JWT key ID must not be blank.");
                }
                if (retiringKeyId.equals(activeKeyId)) {
                    throw new IllegalStateException("Retiring key ID [" + retiringKeyId + "] duplicates the active key ID.");
                }

                byte[] retiringSecretBytes = validateBase64Secret(retiringSecret, "retiring key [" + retiringKeyId + "]");
                validateKeyEntropy(retiringSecretBytes, "retiring key [" + retiringKeyId + "]");
                validateProductionSecret(retiringSecret, "retiring key [" + retiringKeyId + "]");
            }
        }

        int retiringCount = (retiringKeys != null) ? retiringKeys.size() : 0;
        log.info("JWT cryptographic configuration successfully validated. ActiveKeyId: '{}', RetiringKeys: {}",
                activeKeyId, retiringCount);
    }

    private byte[] validateBase64Secret(String secret, String keyDescription) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret for " + keyDescription + " must not be blank.");
        }
        try {
            return Base64.getDecoder().decode(secret);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("JWT secret for " + keyDescription + " is not valid Base64 encoded: " + ex.getMessage());
        }
    }

    private void validateKeyEntropy(byte[] secretBytes, String keyDescription) {
        if (secretBytes.length < MIN_KEY_BYTES) {
            throw new IllegalStateException("JWT secret for " + keyDescription + " contains " + secretBytes.length +
                    " bytes. HS256 requires at least " + MIN_KEY_BYTES + " bytes (256 bits) of entropy.");
        }
    }

    private void validateProductionSecret(String secret, String keyDescription) {
        List<String> activeProfiles = Arrays.asList(environment.getActiveProfiles());
        boolean isDevOrTest = activeProfiles.contains("dev") || activeProfiles.contains("test");

        if (!isDevOrTest) {
            if (KNOWN_PLACEHOLDER_SECRETS.contains(secret.trim())) {
                throw new IllegalStateException("Known development placeholder secret rejected in production for " +
                        keyDescription + ". Configure a cryptographically secure random secret via environment variables.");
            }
        }
    }
}
