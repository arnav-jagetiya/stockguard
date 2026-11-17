package com.stockguard.security.filter;

import com.stockguard.security.config.SecurityProperties;
import com.stockguard.security.handler.SecurityErrorResponseUtils;
import com.stockguard.security.model.StockGuardPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Server-side mutation guard filter for public demo visitor sessions.
 * <p>
 * Intercepts all state-changing operations (POST, PUT, PATCH, DELETE) when the authenticated
 * principal is the designated demo visitor identity.
 * <p>
 * Permits only session lifecycle endpoints:
 * <ul>
 *   <li>{@code POST /api/auth/refresh}</li>
 *   <li>{@code POST /api/auth/logout}</li>
 * </ul>
 * Rejects all other mutation requests with HTTP 403 Forbidden and error code
 * {@code DEMO_MODE_MUTATION_PROHIBITED}.
 */
public class DemoModeMutationGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DemoModeMutationGuardFilter.class);

    public static final String DEMO_MUTATION_PROHIBITED_CODE = "DEMO_MODE_MUTATION_PROHIBITED";
    public static final String DEMO_MUTATION_PROHIBITED_MESSAGE =
            "Demo visitor sessions are read-only. State mutations are prohibited in public demo mode.";

    private static final Set<String> MUTATION_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> ALLOWED_DEMO_MUTATION_PATHS = Set.of(
            "/api/auth/refresh",
            "/api/auth/logout"
    );

    private final SecurityProperties securityProperties;

    public DemoModeMutationGuardFilter(SecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String method = request.getMethod();

        if (MUTATION_METHODS.contains(method.toUpperCase())) {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

            if (authentication != null && authentication.isAuthenticated()
                    && authentication.getPrincipal() instanceof StockGuardPrincipal principal) {

                if (isDemoVisitor(principal.email())) {
                    String path = request.getRequestURI();

                    if (!isAllowedDemoMutationPath(path)) {
                        log.warn("Blocked mutation attempt in demo mode: method {} on path {} by demo visitor {}",
                                method, path, principal.email());

                        SecurityErrorResponseUtils.writeJsonError(
                                response,
                                HttpServletResponse.SC_FORBIDDEN,
                                "Forbidden",
                                DEMO_MUTATION_PROHIBITED_CODE,
                                DEMO_MUTATION_PROHIBITED_MESSAGE,
                                path
                        );
                        return;
                    }
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isDemoVisitor(String email) {
        if (email == null) {
            return false;
        }
        String configuredDemoEmail = "demo.visitor@stockguard.dev";
        if (securityProperties != null && securityProperties.getPublicDemo() != null
                && securityProperties.getPublicDemo().getDemoEmail() != null) {
            configuredDemoEmail = securityProperties.getPublicDemo().getDemoEmail();
        }
        return configuredDemoEmail.equalsIgnoreCase(email.trim());
    }

    private boolean isAllowedDemoMutationPath(String path) {
        if (path == null) {
            return false;
        }
        for (String allowedPath : ALLOWED_DEMO_MUTATION_PATHS) {
            if (path.equals(allowedPath)) {
                return true;
            }
        }
        return false;
    }
}
