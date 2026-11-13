package com.stockguard.security.filter;

import com.stockguard.security.config.SecurityProperties;
import com.stockguard.security.handler.SecurityErrorResponseUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Set;

/**
 * Custom CSRF protection filter for cookie-authenticated endpoints (e.g. {@code /api/auth/refresh}, {@code /api/auth/logout}).
 * <p>
 * Enforces dual protection:
 * <ol>
 *   <li>Origin / Referer verification against configured CORS allowed origins.</li>
 *   <li>Mandatory custom header assertion ({@code X-StockGuard-CSRF: 1}), which browsers cannot forge in cross-site requests.</li>
 * </ol>
 */
public class CsrfProtectionFilter extends OncePerRequestFilter {

    public static final String CSRF_HEADER_NAME = "X-StockGuard-CSRF";
    private static final Set<String> PROTECTED_PATHS = Set.of(
            "/api/auth/refresh",
            "/api/auth/logout"
    );

    private final SecurityProperties securityProperties;

    public CsrfProtectionFilter(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        // CSRF protection applies strictly to state-changing operations on cookie-authenticated auth endpoints
        if (isProtectedEndpoint(path, method)) {
            // 1. Origin verification
            String origin = request.getHeader("Origin");
            if (origin != null && !origin.isBlank()) {
                if (!isAllowedOrigin(origin)) {
                    SecurityErrorResponseUtils.writeJsonError(
                            response,
                            HttpStatus.FORBIDDEN.value(),
                            "Forbidden",
                            "Origin not permitted: " + origin,
                            request.getRequestURI()
                    );
                    return;
                }
            }

            // 2. Custom header validation
            String csrfHeader = request.getHeader(CSRF_HEADER_NAME);
            if (csrfHeader == null || csrfHeader.isBlank() || (!"1".equals(csrfHeader.trim()) && !"true".equalsIgnoreCase(csrfHeader.trim()))) {
                SecurityErrorResponseUtils.writeJsonError(
                        response,
                        HttpStatus.FORBIDDEN.value(),
                        "Forbidden",
                        "Missing or invalid required CSRF header: " + CSRF_HEADER_NAME,
                        request.getRequestURI()
                );
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isProtectedEndpoint(String path, String method) {
        if (!"POST".equalsIgnoreCase(method)) {
            return false;
        }
        for (String protectedPath : PROTECTED_PATHS) {
            if (path.equals(protectedPath) || path.startsWith(protectedPath + "/")) {
                return true;
            }
        }
        return false;
    }

    boolean isAllowedOrigin(String origin) {
        if (origin == null || origin.isBlank()) {
            return false;
        }

        String trimmedOrigin = origin.trim();
        // Disallow wildcard or null literal origin
        if ("*".equals(trimmedOrigin) || "null".equalsIgnoreCase(trimmedOrigin)) {
            return false;
        }

        // Fail closed for malformed origin URI structures (path, query, fragment or missing host/scheme)
        try {
            URI uri = URI.create(trimmedOrigin);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return false;
            }
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !"/".equals(uri.getRawPath())) {
                return false;
            }
            if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getUserInfo() != null) {
                return false;
            }
        } catch (IllegalArgumentException ex) {
            return false;
        }

        List<String> allowed = securityProperties.getCors().getAllowedOrigins();
        if (allowed == null || allowed.isEmpty()) {
            return false;
        }
        for (String allowedOrigin : allowed) {
            if (allowedOrigin != null && !"*".equals(allowedOrigin) && allowedOrigin.equalsIgnoreCase(trimmedOrigin)) {
                return true;
            }
        }
        return false;
    }
}
