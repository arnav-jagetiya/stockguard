package com.stockguard.security.util;

import com.stockguard.security.config.SecurityProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Helper utility for building and extracting HTTP-only refresh token cookies
 * according to the environment specification in ADR-010.
 */
@Component
public class RefreshTokenCookieHelper {

    private static final Duration REFRESH_COOKIE_MAX_AGE = Duration.ofDays(7);
    private final SecurityProperties securityProperties;

    public RefreshTokenCookieHelper(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    /**
     * Extracts the raw opaque refresh token from the request cookies matching the configured cookie name.
     */
    public Optional<String> extractRefreshToken(HttpServletRequest request) {
        if (request == null || request.getCookies() == null) {
            return Optional.empty();
        }

        String targetCookieName = securityProperties.getCookie().getName();
        for (Cookie cookie : request.getCookies()) {
            if (targetCookieName.equals(cookie.getName())) {
                String value = cookie.getValue();
                if (value != null && !value.isBlank()) {
                    return Optional.of(value.trim());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Builds a Set-Cookie response header containing the rotated raw refresh token.
     */
    public ResponseCookie buildRefreshCookie(String rawRefreshToken) {
        SecurityProperties.Cookie props = securityProperties.getCookie();
        return ResponseCookie.from(props.getName(), rawRefreshToken)
                .httpOnly(props.isHttpOnly())
                .secure(props.isSecure())
                .path(props.getPath())
                .sameSite(props.getSameSite())
                .maxAge(REFRESH_COOKIE_MAX_AGE)
                .build();
    }

    /**
     * Builds a Set-Cookie response header that clears the refresh token cookie.
     */
    public ResponseCookie buildClearRefreshCookie() {
        SecurityProperties.Cookie props = securityProperties.getCookie();
        return ResponseCookie.from(props.getName(), "")
                .httpOnly(props.isHttpOnly())
                .secure(props.isSecure())
                .path(props.getPath())
                .sameSite(props.getSameSite())
                .maxAge(Duration.ZERO)
                .build();
    }
}
