package com.stockguard.security.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Type-safe configuration properties for STOCKGUARD security and token lifecycles.
 * <p>
 * Binds properties under the {@code stockguard.security} prefix according to ADR-010.
 */
@ConfigurationProperties(prefix = "stockguard.security")
@Validated
public class SecurityProperties {

    @Valid
    @NotNull
    private Jwt jwt = new Jwt();

    @Valid
    @NotNull
    private Cookie cookie = new Cookie();

    @Valid
    @NotNull
    private RateLimit rateLimit = new RateLimit();

    @Valid
    @NotNull
    private Cors cors = new Cors();

    @Valid
    @NotNull
    private Bootstrap bootstrap = new Bootstrap();

    @Min(4)
    @Max(31)
    private int passwordEncoderCost = 12;

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public void setBootstrap(Bootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    public int getPasswordEncoderCost() {
        return passwordEncoderCost;
    }

    public void setPasswordEncoderCost(int passwordEncoderCost) {
        this.passwordEncoderCost = passwordEncoderCost;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public void setJwt(Jwt jwt) {
        this.jwt = jwt;
    }

    public Cookie getCookie() {
        return cookie;
    }

    public void setCookie(Cookie cookie) {
        this.cookie = cookie;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Cors getCors() {
        return cors;
    }

    public void setCors(Cors cors) {
        this.cors = cors;
    }

    public static class Jwt {

        @Valid
        @NotNull
        private ActiveKey activeKey = new ActiveKey();

        /**
         * Map of retiring key IDs to their Base64-encoded secret keys.
         * Used for signature verification during zero-downtime key rotation.
         */
        private Map<String, String> retiringKeys = new HashMap<>();

        @NotBlank
        private String issuer = "stockguard-platform";

        @NotBlank
        private String audience = "stockguard-api";

        @Min(1)
        @Max(1440)
        private int accessTokenExpirationMinutes = 15;

        public ActiveKey getActiveKey() {
            return activeKey;
        }

        public void setActiveKey(ActiveKey activeKey) {
            this.activeKey = activeKey;
        }

        public Map<String, String> getRetiringKeys() {
            return retiringKeys;
        }

        public void setRetiringKeys(Map<String, String> retiringKeys) {
            this.retiringKeys = retiringKeys;
        }

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public int getAccessTokenExpirationMinutes() {
            return accessTokenExpirationMinutes;
        }

        public void setAccessTokenExpirationMinutes(int accessTokenExpirationMinutes) {
            this.accessTokenExpirationMinutes = accessTokenExpirationMinutes;
        }

        public static class ActiveKey {

            @NotBlank
            private String id = "sg-2026-v1";

            @NotBlank
            private String secret;

            public String getId() {
                return id;
            }

            public void setId(String id) {
                this.id = id;
            }

            public String getSecret() {
                return secret;
            }

            public void setSecret(String secret) {
                this.secret = secret;
            }
        }
    }

    public static class Cookie {

        @NotBlank
        private String name = "__Secure-stockguard_refresh";

        @NotBlank
        private String path = "/api/auth";

        private boolean secure = true;

        private boolean httpOnly = true;

        @NotBlank
        private String sameSite = "Strict";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public boolean isSecure() {
            return secure;
        }

        public void setSecure(boolean secure) {
            this.secure = secure;
        }

        public boolean isHttpOnly() {
            return httpOnly;
        }

        public void setHttpOnly(boolean httpOnly) {
            this.httpOnly = httpOnly;
        }

        public String getSameSite() {
            return sameSite;
        }

        public void setSameSite(String sameSite) {
            this.sameSite = sameSite;
        }
    }

    public static class RateLimit {

        @Min(1)
        private int loginPermitsPerMinute = 5;

        @Min(1)
        private int refreshPermitsPerMinute = 20;

        public int getLoginPermitsPerMinute() {
            return loginPermitsPerMinute;
        }

        public void setLoginPermitsPerMinute(int loginPermitsPerMinute) {
            this.loginPermitsPerMinute = loginPermitsPerMinute;
        }

        public int getRefreshPermitsPerMinute() {
            return refreshPermitsPerMinute;
        }

        public void setRefreshPermitsPerMinute(int refreshPermitsPerMinute) {
            this.refreshPermitsPerMinute = refreshPermitsPerMinute;
        }
    }

    public static class Cors {

        @NotEmpty
        private List<String> allowedOrigins = List.of("http://localhost:5173");

        public List<String> getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(List<String> allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }
    }

    public static class Bootstrap {

        private boolean enabled = false;

        private String adminEmail;

        private String adminPassword;

        private String adminFullName;

        private String orgName = "Primary Organization";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAdminEmail() {
            return adminEmail;
        }

        public void setAdminEmail(String adminEmail) {
            this.adminEmail = adminEmail;
        }

        public String getAdminPassword() {
            return adminPassword;
        }

        public void setAdminPassword(String adminPassword) {
            this.adminPassword = adminPassword;
        }

        public String getAdminFullName() {
            return adminFullName;
        }

        public void setAdminFullName(String adminFullName) {
            this.adminFullName = adminFullName;
        }

        public String getOrgName() {
            return orgName;
        }

        public void setOrgName(String orgName) {
            this.orgName = orgName;
        }
    }
}
