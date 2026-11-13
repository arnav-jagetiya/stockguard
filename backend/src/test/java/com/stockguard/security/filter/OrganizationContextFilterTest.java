package com.stockguard.security.filter;

import com.stockguard.organization.domain.Permission;
import com.stockguard.organization.domain.Role;
import com.stockguard.security.context.OrganizationContext;
import com.stockguard.security.context.OrganizationContextHolder;
import com.stockguard.security.model.StockGuardAuthenticationToken;
import com.stockguard.security.model.StockGuardPrincipal;
import com.stockguard.security.service.TenantMembershipValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class OrganizationContextFilterTest {

    private UUID userId;
    private UUID sessionId;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        sessionId = UUID.randomUUID();
        orgId = UUID.randomUUID();
        SecurityContextHolder.clearContext();
        OrganizationContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        OrganizationContextHolder.clearContext();
    }

    private void setAuthenticatedUser() {
        StockGuardPrincipal principal = new StockGuardPrincipal(userId, sessionId, "user@stockguard.io");
        StockGuardAuthenticationToken token = new StockGuardAuthenticationToken(
                principal,
                Collections.emptyList()
        );
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    @Test
    @DisplayName("Exempt endpoints bypass X-Organization-Id validation")
    void exemptEndpointsBypassFilter() throws ServletException, IOException {
        TenantMembershipValidator stubValidator = (u, o) -> Optional.empty();
        OrganizationContextFilter filter = new OrganizationContextFilter(stubValidator);

        List<String> exemptUris = List.of(
                "/actuator/health",
                "/api/auth/login",
                "/api/auth/refresh",
                "/api/auth/me",
                "/api/auth/switch-org",
                "/api/auth/logout"
        );

        for (String uri : exemptUris) {
            MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
            MockHttpServletResponse response = new MockHttpServletResponse();
            AtomicBoolean chainInvoked = new AtomicBoolean(false);

            filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

            assertThat(chainInvoked.get()).isTrue();
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }
    }

    @Test
    @DisplayName("Preflight CORS OPTIONS request bypasses X-Organization-Id check")
    void optionsBypassesFilter() throws ServletException, IOException {
        TenantMembershipValidator stubValidator = (u, o) -> Optional.empty();
        OrganizationContextFilter filter = new OrganizationContextFilter(stubValidator);

        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(chainInvoked.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("Organization-scoped endpoint without authentication lets downstream filter handle 401")
    void unauthenticatedRequestBypassesToDownstream() throws ServletException, IOException {
        TenantMembershipValidator stubValidator = (u, o) -> Optional.empty();
        OrganizationContextFilter filter = new OrganizationContextFilter(stubValidator);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(chainInvoked.get()).isTrue();
    }

    @Test
    @DisplayName("Authenticated request missing X-Organization-Id header returns 400 Bad Request")
    void missingHeaderReturns400() throws ServletException, IOException {
        setAuthenticatedUser();
        TenantMembershipValidator stubValidator = (u, o) -> Optional.empty();
        OrganizationContextFilter filter = new OrganizationContextFilter(stubValidator);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(chainInvoked.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(response.getContentAsString()).contains("Missing mandatory organization header 'X-Organization-Id'");
    }

    @Test
    @DisplayName("Authenticated request with malformed X-Organization-Id header returns 400 Bad Request")
    void malformedHeaderReturns400() throws ServletException, IOException {
        setAuthenticatedUser();
        TenantMembershipValidator stubValidator = (u, o) -> Optional.empty();
        OrganizationContextFilter filter = new OrganizationContextFilter(stubValidator);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader(OrganizationContextFilter.ORG_HEADER_NAME, "not-a-valid-uuid");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(chainInvoked.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_BAD_REQUEST);
        assertThat(response.getContentAsString()).contains("Invalid organization ID format in 'X-Organization-Id' header");
    }

    @Test
    @DisplayName("Authenticated request with no active tenant membership returns 403 Forbidden")
    void noMembershipReturns403() throws ServletException, IOException {
        setAuthenticatedUser();
        TenantMembershipValidator stubValidator = (u, o) -> Optional.empty();
        OrganizationContextFilter filter = new OrganizationContextFilter(stubValidator);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader(OrganizationContextFilter.ORG_HEADER_NAME, orgId.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(chainInvoked.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains("User has no active membership in organization");
    }

    @Test
    @DisplayName("Authenticated request with inactive tenant membership returns 403 Forbidden")
    void inactiveMembershipReturns403() throws ServletException, IOException {
        setAuthenticatedUser();
        TenantMembershipValidator stubValidator = (u, o) -> Optional.of(
                new TenantMembershipValidator.TenantMembershipDetails(orgId, userId, Role.OPERATOR, false)
        );
        OrganizationContextFilter filter = new OrganizationContextFilter(stubValidator);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader(OrganizationContextFilter.ORG_HEADER_NAME, orgId.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(chainInvoked.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains("User has no active membership in organization");
    }

    @Test
    @DisplayName("Database failure during membership check fails closed with 500 Internal Server Error")
    void databaseFailureFailsClosedWith500() throws ServletException, IOException {
        setAuthenticatedUser();
        TenantMembershipValidator stubValidator = (u, o) -> {
            throw new RuntimeException("PostgreSQL connection timeout");
        };
        OrganizationContextFilter filter = new OrganizationContextFilter(stubValidator);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader(OrganizationContextFilter.ORG_HEADER_NAME, orgId.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        filter.doFilter(request, response, (req, res) -> chainInvoked.set(true));

        assertThat(chainInvoked.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        assertThat(response.getContentAsString()).contains("Failed to verify organization membership due to database error");
    }

    @Test
    @DisplayName("Valid organization request populates context, enriches authorities, and clears context in finally")
    void validOrganizationRequestSucceedsAndClearsContext() throws ServletException, IOException {
        setAuthenticatedUser();
        TenantMembershipValidator stubValidator = (u, o) -> Optional.of(
                new TenantMembershipValidator.TenantMembershipDetails(orgId, userId, Role.ADMIN, true)
        );
        OrganizationContextFilter filter = new OrganizationContextFilter(stubValidator);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader(OrganizationContextFilter.ORG_HEADER_NAME, orgId.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicBoolean insideChainValidated = new AtomicBoolean(false);

        FilterChain chain = (req, res) -> {
            // Assert OrganizationContextHolder is set inside filter execution
            Optional<OrganizationContext> ctxOpt = OrganizationContextHolder.getContext();
            assertThat(ctxOpt).isPresent();
            OrganizationContext ctx = ctxOpt.get();
            assertThat(ctx.organizationId()).isEqualTo(orgId);
            assertThat(ctx.userId()).isEqualTo(userId);
            assertThat(ctx.role()).isEqualTo(Role.ADMIN);
            assertThat(ctx.hasPermission(Permission.PRODUCT_MANAGE)).isTrue();
            assertThat(ctx.hasPermission("membership:manage")).isTrue();

            // Assert Spring Security Authentication authorities are enriched
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getAuthorities()).anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
            assertThat(auth.getAuthorities()).anyMatch(a -> a.getAuthority().equals("membership:manage"));
            assertThat(auth.getAuthorities()).anyMatch(a -> a.getAuthority().equals("inventory:adjust"));

            insideChainValidated.set(true);
        };

        filter.doFilter(request, response, chain);

        assertThat(insideChainValidated.get()).isTrue();
        // Assert ThreadLocal is deterministically cleared after filter completes
        assertThat(OrganizationContextHolder.getContext()).isEmpty();
    }
}
