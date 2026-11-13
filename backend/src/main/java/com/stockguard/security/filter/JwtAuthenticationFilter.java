package com.stockguard.security.filter;

import com.stockguard.security.exception.JwtValidationException;
import com.stockguard.security.handler.SecurityJsonAuthenticationEntryPoint;
import com.stockguard.security.model.StockGuardAuthenticationToken;
import com.stockguard.security.model.StockGuardPrincipal;
import com.stockguard.security.model.ValidatedTokenClaims;
import com.stockguard.security.service.DatabaseSessionValidator;
import com.stockguard.security.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

/**
 * Spring Security filter for Bearer JWT extraction, cryptographic validation,
 * and authoritative database session/user verification.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final DatabaseSessionValidator databaseSessionValidator;
    private final SecurityJsonAuthenticationEntryPoint authenticationEntryPoint;

    public JwtAuthenticationFilter(
            JwtService jwtService,
            DatabaseSessionValidator databaseSessionValidator,
            SecurityJsonAuthenticationEntryPoint authenticationEntryPoint
    ) {
        this.jwtService = jwtService;
        this.databaseSessionValidator = databaseSessionValidator;
        this.authenticationEntryPoint = authenticationEntryPoint;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader(AUTHORIZATION_HEADER);
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            // No Bearer token presented; proceed along chain
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isEmpty()) {
            request.setAttribute("stockguard.auth.error_message", "Bearer token must not be blank");
            authenticationEntryPoint.commence(request, response, new BadCredentialsException("Blank Bearer token"));
            return;
        }

        ValidatedTokenClaims claims;
        try {
            claims = jwtService.parseAndValidateToken(token);
        } catch (JwtValidationException ex) {
            log.warn("JWT validation failed for URI {}: {}", request.getRequestURI(), ex.getMessage());
            request.setAttribute("stockguard.auth.error_message", ex.getMessage());
            authenticationEntryPoint.commence(request, response, new BadCredentialsException(ex.getMessage(), ex));
            return;
        } catch (Exception ex) {
            log.error("Unexpected error validating JWT: {}", ex.getMessage());
            request.setAttribute("stockguard.auth.error_message", "Invalid authentication token");
            authenticationEntryPoint.commence(request, response, new BadCredentialsException("Token parsing error", ex));
            return;
        }

        // Authoritative Database Validation (s.id = :sessionId AND s.user_id = :userId)
        DatabaseSessionValidator.SessionValidationResult sessionResult;
        try {
            sessionResult = databaseSessionValidator.validateSession(claims.sessionId(), claims.userId());
        } catch (Exception ex) {
            log.error("Database error during session validation: {}", ex.getMessage());
            request.setAttribute("stockguard.auth.error_message", "Authentication service temporarily unavailable");
            authenticationEntryPoint.commence(request, response, new BadCredentialsException("Session validation failed closed", ex));
            return;
        }

        if (!sessionResult.isValid()) {
            log.warn("Database session validation rejected for user {} (session {}): {}",
                    claims.userId(), claims.sessionId(), sessionResult.errorMessage());
            request.setAttribute("stockguard.auth.error_message", sessionResult.errorMessage());
            authenticationEntryPoint.commence(request, response, new BadCredentialsException(sessionResult.errorMessage()));
            return;
        }

        // Token and Session are valid; establish SecurityContext
        StockGuardPrincipal principal = new StockGuardPrincipal(claims.userId(), claims.sessionId(), claims.email());
        StockGuardAuthenticationToken authToken = new StockGuardAuthenticationToken(
                principal,
                token,
                Collections.emptyList() // Authorities mapped in OrganizationContextFilter for tenant-scoped endpoints
        );

        SecurityContextHolder.getContext().setAuthentication(authToken);

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Ensure authentication is maintained for downstream processing but cleaned on request completion
        }
    }
}
