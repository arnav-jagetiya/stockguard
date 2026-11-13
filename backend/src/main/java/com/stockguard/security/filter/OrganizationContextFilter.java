package com.stockguard.security.filter;

import com.stockguard.organization.domain.Role;
import com.stockguard.organization.domain.RolePermissions;
import com.stockguard.security.context.OrganizationContext;
import com.stockguard.security.context.OrganizationContextHolder;
import com.stockguard.security.handler.SecurityErrorResponseUtils;
import com.stockguard.security.model.StockGuardAuthenticationToken;
import com.stockguard.security.model.StockGuardPrincipal;
import com.stockguard.security.service.TenantMembershipValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Filter asserting explicit organization context via {@code X-Organization-Id} on all
 * organization-scoped endpoints per ADR-011.
 * <p>
 * Key architectural responsibilities:
 * <ul>
 *   <li>Exempts public endpoints and user-scoped endpoints (/api/auth/**, /actuator/**)</li>
 *   <li>Asserts mandatory {@code X-Organization-Id} header presence on org-scoped requests</li>
 *   <li>Rejects missing header with 400 Bad Request (no silent fallbacks)</li>
 *   <li>Rejects malformed UUID with 400 Bad Request</li>
 *   <li>Authoritatively queries PostgreSQL for active tenant membership</li>
 *   <li>Rejects missing or inactive memberships with 403 Forbidden</li>
 *   <li>Dynamically binds role and granular permissions into Spring Security and {@link OrganizationContextHolder}</li>
 *   <li>Ensures ThreadLocal is cleared deterministically in a finally block</li>
 * </ul>
 */
public class OrganizationContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OrganizationContextFilter.class);

    public static final String ORG_HEADER_NAME = "X-Organization-Id";

    private final TenantMembershipValidator membershipValidator;

    public OrganizationContextFilter(TenantMembershipValidator membershipValidator) {
        this.membershipValidator = membershipValidator;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // 1. Bypass preflight CORS OPTIONS requests
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();

        // 2. Bypass public and user-scoped endpoints
        if (isExemptPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. For organization-scoped requests, assert user authentication
        Authentication currentAuth = SecurityContextHolder.getContext().getAuthentication();
        if (currentAuth == null || !currentAuth.isAuthenticated()
                || !(currentAuth.getPrincipal() instanceof StockGuardPrincipal principal)) {
            // Let downstream security filters handle unauthenticated requests (401)
            filterChain.doFilter(request, response);
            return;
        }

        // 4. Assert mandatory X-Organization-Id header
        String orgHeader = request.getHeader(ORG_HEADER_NAME);
        if (orgHeader == null || orgHeader.trim().isEmpty()) {
            log.warn("Missing {} header for org-scoped URI: {}", ORG_HEADER_NAME, path);
            SecurityErrorResponseUtils.writeJsonError(
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "Bad Request",
                    "Missing mandatory organization header '" + ORG_HEADER_NAME + "'",
                    path
            );
            return;
        }

        // 5. Assert valid UUID syntax
        UUID organizationId;
        try {
            organizationId = UUID.fromString(orgHeader.trim());
        } catch (IllegalArgumentException ex) {
            log.warn("Invalid {} header format '{}' for URI: {}", ORG_HEADER_NAME, orgHeader, path);
            SecurityErrorResponseUtils.writeJsonError(
                    response,
                    HttpServletResponse.SC_BAD_REQUEST,
                    "Bad Request",
                    "Invalid organization ID format in '" + ORG_HEADER_NAME + "' header",
                    path
            );
            return;
        }

        // 6. Authoritative PostgreSQL tenant membership verification
        Optional<TenantMembershipValidator.TenantMembershipDetails> membershipOpt;
        try {
            membershipOpt = membershipValidator.validateMembership(principal.userId(), organizationId);
        } catch (Exception ex) {
            log.error("Database error during tenant membership validation for user {} and org {}: {}",
                    principal.userId(), organizationId, ex.getMessage(), ex);
            SecurityErrorResponseUtils.writeJsonError(
                    response,
                    HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Internal Server Error",
                    "Failed to verify organization membership due to database error",
                    path
            );
            return;
        }

        if (membershipOpt.isEmpty() || !membershipOpt.get().active()) {
            log.warn("Tenant access denied: user {} has no active membership in organization {}",
                    principal.userId(), organizationId);
            SecurityErrorResponseUtils.writeJsonError(
                    response,
                    HttpServletResponse.SC_FORBIDDEN,
                    "Forbidden",
                    "User has no active membership in organization: " + organizationId,
                    path
            );
            return;
        }

        // 7. Enforce role and granular permissions dynamically
        TenantMembershipValidator.TenantMembershipDetails details = membershipOpt.get();
        Role role = details.role();
        Set<String> permissionAuthorities = RolePermissions.getPermissionAuthoritiesForRole(role);

        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role.name()));
        for (String perm : permissionAuthorities) {
            authorities.add(new SimpleGrantedAuthority(perm));
        }

        StockGuardAuthenticationToken enrichedAuth = new StockGuardAuthenticationToken(
                principal,
                authorities
        );
        SecurityContextHolder.getContext().setAuthentication(enrichedAuth);

        // 8. Bind OrganizationContext to current thread
        OrganizationContext orgContext = new OrganizationContext(
                organizationId,
                principal.userId(),
                role,
                permissionAuthorities
        );
        OrganizationContextHolder.setContext(orgContext);

        try {
            filterChain.doFilter(request, response);
        } finally {
            OrganizationContextHolder.clearContext();
        }
    }

    private boolean isExemptPath(String path) {
        if (path == null) {
            return false;
        }
        return path.startsWith("/actuator/")
                || path.startsWith("/api/auth/")
                || !path.startsWith("/api/");
    }
}
