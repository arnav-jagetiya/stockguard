package com.stockguard.security.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityPropertiesValidationTest {

    private static final String VALID_KEY_ID = "sg-2026-v1";
    // 32-byte valid Base64 secret
    private static final String VALID_32_BYTE_BASE64 = "YWN0aXZlLXNlY3JldC0zMi1ieXRlcy1jcnlwdG8ta2V5LTIwMjY=";
    private static final String VALID_RETIRING_KEY_ID = "sg-2025-v0";
    private static final String VALID_RETIRING_BASE64 = "cmV0aXJpbmctc2VjcmV0LTMyLWJ5dGVzLWtleS0yMDI2IQ==";

    private SecurityProperties properties;
    private MockEnvironment environment;

    @BeforeEach
    void setUp() {
        properties = new SecurityProperties();
        properties.getJwt().getActiveKey().setId(VALID_KEY_ID);
        properties.getJwt().getActiveKey().setSecret(VALID_32_BYTE_BASE64);
        properties.getJwt().setIssuer("stockguard-platform");
        properties.getJwt().setAudience("stockguard-api");

        environment = new MockEnvironment();
        environment.setActiveProfiles("test");
    }

    @Test
    @DisplayName("Valid active secret and properties pass validation")
    void validConfigurationPasses() {
        SecurityKeyValidator validator = new SecurityKeyValidator(properties, environment);
        assertThatCode(validator::validateSecurityConfiguration).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Invalid Base64 active secret fails validation")
    void invalidBase64Fails() {
        properties.getJwt().getActiveKey().setSecret("%%%not-valid-base64%%%");
        SecurityKeyValidator validator = new SecurityKeyValidator(properties, environment);

        assertThatThrownBy(validator::validateSecurityConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid Base64");
    }

    @Test
    @DisplayName("Secret shorter than 32 bytes (256 bits) fails validation")
    void secretShorterThan32BytesFails() {
        // "c2hvcnQ=" decodes to "short" (5 bytes)
        properties.getJwt().getActiveKey().setSecret("c2hvcnQtMTYtYnl0ZXMtIQ=="); // 16 bytes
        SecurityKeyValidator validator = new SecurityKeyValidator(properties, environment);

        assertThatThrownBy(validator::validateSecurityConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires at least 32 bytes (256 bits)");
    }

    @Test
    @DisplayName("Missing active key ID fails validation")
    void missingKeyIdFails() {
        properties.getJwt().getActiveKey().setId("   ");
        SecurityKeyValidator validator = new SecurityKeyValidator(properties, environment);

        assertThatThrownBy(validator::validateSecurityConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Active JWT key ID");
    }

    @Test
    @DisplayName("Missing issuer fails validation")
    void missingIssuerFails() {
        properties.getJwt().setIssuer("");
        SecurityKeyValidator validator = new SecurityKeyValidator(properties, environment);

        assertThatThrownBy(validator::validateSecurityConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT issuer");
    }

    @Test
    @DisplayName("Missing audience fails validation")
    void missingAudienceFails() {
        properties.getJwt().setAudience("  ");
        SecurityKeyValidator validator = new SecurityKeyValidator(properties, environment);

        assertThatThrownBy(validator::validateSecurityConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT audience");
    }

    @Test
    @DisplayName("Invalid retiring key (short secret or invalid Base64) fails validation")
    void invalidRetiringKeyFails() {
        properties.getJwt().setRetiringKeys(Map.of("old-key", "c2hvcnQ=")); // 5 bytes
        SecurityKeyValidator validator = new SecurityKeyValidator(properties, environment);

        assertThatThrownBy(validator::validateSecurityConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires at least 32 bytes");
    }

    @Test
    @DisplayName("Retiring key duplicating the active key ID fails validation")
    void duplicateKeyIdFails() {
        properties.getJwt().setRetiringKeys(Map.of(VALID_KEY_ID, VALID_RETIRING_BASE64));
        SecurityKeyValidator validator = new SecurityKeyValidator(properties, environment);

        assertThatThrownBy(validator::validateSecurityConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicates the active key ID");
    }

    @Test
    @DisplayName("Production environment rejects known development placeholder secret")
    void productionRejectsPlaceholderSecret() {
        environment.setActiveProfiles("prod");
        properties.getJwt().getActiveKey().setSecret("vWvE+H3M7P+T0GfQx4zL+cK9p9V5hW8J2eT4rY6uI0A=");
        SecurityKeyValidator validator = new SecurityKeyValidator(properties, environment);

        assertThatThrownBy(validator::validateSecurityConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Known development placeholder secret rejected in production");
    }

    @Test
    @DisplayName("Invalid JWT key configuration halts Spring Boot application startup")
    void invalidConfigurationHaltsStartup() {
        org.springframework.boot.test.context.runner.ApplicationContextRunner contextRunner =
                new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                        .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(InterimSecurityConfig.class))
                        .withUserConfiguration(SecurityKeyValidator.class)
                        .withPropertyValues(
                                "stockguard.security.jwt.active-key.id=sg-test",
                                "stockguard.security.jwt.active-key.secret=c2hvcnQ=", // 5 bytes
                                "stockguard.security.jwt.issuer=test-iss",
                                "stockguard.security.jwt.audience=test-aud"
                        );

        contextRunner.run(context -> {
            org.assertj.core.api.Assertions.assertThat(context).hasFailed();
            org.assertj.core.api.Assertions.assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("JWT secret for active key [sg-test] contains 5 bytes. HS256 requires at least 32 bytes (256 bits) of entropy.");
        });
    }
}
