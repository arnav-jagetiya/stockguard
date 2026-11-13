package com.stockguard.security.controller;

import com.stockguard.organization.domain.Role;
import com.stockguard.security.config.SecurityProperties;
import com.stockguard.security.dto.*;
import com.stockguard.security.exception.AuthenticationFailedException;
import com.stockguard.security.exception.SessionRevokedException;
import com.stockguard.security.exception.TokenReuseException;
import com.stockguard.security.model.LoginCommand;
import com.stockguard.security.model.LoginResult;
import com.stockguard.security.model.RefreshResult;
import com.stockguard.security.model.StockGuardPrincipal;
import com.stockguard.security.service.AuthenticationService;
import com.stockguard.security.service.UserContextQueryService;
import com.stockguard.security.util.RefreshTokenCookieHelper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerTest {

    private SecurityProperties properties;
    private RefreshTokenCookieHelper cookieHelper;
    private StubAuthenticationService authService;
    private StubUserContextQueryService queryService;
    private AuthController controller;

    @BeforeEach
    void setUp() {
        properties = new SecurityProperties();
        properties.getCookie().setName("stockguard_test_refresh");
        properties.getCookie().setPath("/api/auth");
        properties.getCookie().setSecure(false);
        properties.getCookie().setHttpOnly(true);
        properties.getCookie().setSameSite("Lax");

        cookieHelper = new RefreshTokenCookieHelper(properties);
        authService = new StubAuthenticationService();
        queryService = new StubUserContextQueryService();
        controller = new AuthController(authService, queryService, cookieHelper);
    }

    @Test
    @DisplayName("POST /api/auth/login succeeds and sets HttpOnly refresh cookie")
    void loginSucceedsWithCookie() {
        LoginRequest request = new LoginRequest("admin@example.com", "Password123!");
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/auth/login");
        servletRequest.setRemoteAddr("127.0.0.1");
        servletRequest.addHeader(HttpHeaders.USER_AGENT, "TestAgent");

        UUID userId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        authService.nextLoginResult = new LoginResult(
                "access.jwt.token", "raw-refresh-token-123", 900, "Bearer",
                userId, "admin@example.com", UUID.randomUUID()
        );
        queryService.availableOrgs = List.of(new OrganizationSummaryDto(orgId, "Primary Org", Role.ADMIN));

        ResponseEntity<?> response = controller.login(request, servletRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().get(HttpHeaders.SET_COOKIE)).isNotEmpty();
        String cookieHeader = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookieHeader).contains("stockguard_test_refresh=raw-refresh-token-123");
        assertThat(cookieHeader).contains("HttpOnly");

        AuthTokenResponse body = (AuthTokenResponse) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.accessToken()).isEqualTo("access.jwt.token");
        assertThat(body.user().email()).isEqualTo("admin@example.com");
        assertThat(body.availableOrganizations()).hasSize(1);
    }

    @Test
    @DisplayName("POST /api/auth/login fails when credentials are invalid")
    void loginFailsWithInvalidCredentials() {
        LoginRequest request = new LoginRequest("admin@example.com", "WrongPassword");
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/auth/login");

        authService.throwOnLogin = new AuthenticationFailedException("Invalid email or password");

        ResponseEntity<?> response = controller.login(request, servletRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("message")).isEqualTo("Invalid email or password");
    }

    @Test
    @DisplayName("POST /api/auth/login returns 403 when user has zero active memberships")
    void loginFailsWhenNoActiveMemberships() {
        LoginRequest request = new LoginRequest("orphan@example.com", "Password123!");
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/auth/login");

        authService.nextLoginResult = new LoginResult(
                "access.jwt.token", "raw-refresh-token-123", 900, "Bearer",
                UUID.randomUUID(), "orphan@example.com", UUID.randomUUID()
        );
        queryService.availableOrgs = Collections.emptyList();

        ResponseEntity<?> response = controller.login(request, servletRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body.get("message")).isEqualTo("No active organization memberships found for this account");
        assertThat(authService.revokedSessionId).isEqualTo(authService.nextLoginResult.sessionId());
    }

    @Test
    @DisplayName("POST /api/auth/refresh rotates refresh cookie and returns new access token")
    void refreshSucceeds() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/auth/refresh");
        servletRequest.setCookies(new Cookie("stockguard_test_refresh", "old-raw-token"));

        UUID userId = UUID.randomUUID();
        authService.nextRefreshResult = new RefreshResult(
                "new.access.token", "new-raw-refresh-token", 900, "Bearer",
                userId, "user@example.com", UUID.randomUUID()
        );
        queryService.availableOrgs = List.of(new OrganizationSummaryDto(UUID.randomUUID(), "Org", Role.OPERATOR));

        ResponseEntity<?> response = controller.refresh(servletRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cookieHeader = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookieHeader).contains("stockguard_test_refresh=new-raw-refresh-token");

        AuthTokenResponse body = (AuthTokenResponse) response.getBody();
        assertThat(body.accessToken()).isEqualTo("new.access.token");
    }

    @Test
    @DisplayName("POST /api/auth/refresh returns 401 and clears cookie when token reuse is detected")
    void refreshReplayClearsCookieAndReturns401() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/auth/refresh");
        servletRequest.setCookies(new Cookie("stockguard_test_refresh", "replayed-token"));

        authService.throwOnRefresh = new TokenReuseException("Token replay detected");

        ResponseEntity<?> response = controller.refresh(servletRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String cookieHeader = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookieHeader).contains("Max-Age=0");
    }

    @Test
    @DisplayName("POST /api/auth/refresh returns 401 when cookie is missing")
    void refreshWithoutCookieReturns401() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/auth/refresh");

        ResponseEntity<?> response = controller.refresh(servletRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("POST /api/auth/logout revokes session and clears cookie")
    void logoutRevokesSessionAndClearsCookie() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/auth/logout");
        servletRequest.setCookies(new Cookie("stockguard_test_refresh", "active-refresh-token"));

        UUID sessionId = UUID.randomUUID();
        StockGuardPrincipal principal = new StockGuardPrincipal(UUID.randomUUID(), sessionId, "user@example.com");

        ResponseEntity<?> response = controller.logout(servletRequest, principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cookieHeader = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookieHeader).contains("Max-Age=0");
        assertThat(authService.loggedOutToken).isEqualTo("active-refresh-token");
        assertThat(authService.revokedSessionId).isEqualTo(sessionId);
    }

    @Test
    @DisplayName("POST /api/auth/logout is idempotent and safe without cookies or principal")
    void logoutIdempotentWithoutCookies() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/auth/logout");

        ResponseEntity<?> response = controller.logout(servletRequest, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String cookieHeader = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookieHeader).contains("Max-Age=0");
    }

    @Test
    @DisplayName("GET /api/auth/me returns authenticated user and active memberships")
    void getMeSucceeds() {
        UUID userId = UUID.randomUUID();
        StockGuardPrincipal principal = new StockGuardPrincipal(userId, UUID.randomUUID(), "test@example.com");

        queryService.userMeResponse = new UserMeResponse(
                userId, "test@example.com", "Test User", true,
                List.of(new OrganizationMembershipSummaryDto(UUID.randomUUID(), "Org Alpha", Role.MANAGER))
        );

        ResponseEntity<?> response = controller.getCurrentUser(principal);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        UserMeResponse body = (UserMeResponse) response.getBody();
        assertThat(body.id()).isEqualTo(userId);
        assertThat(body.email()).isEqualTo("test@example.com");
        assertThat(body.memberships()).hasSize(1);
    }

    @Test
    @DisplayName("POST /api/auth/switch-org returns target organization context and permissions")
    void switchOrgSucceeds() {
        UUID userId = UUID.randomUUID();
        UUID targetOrgId = UUID.randomUUID();
        StockGuardPrincipal principal = new StockGuardPrincipal(userId, UUID.randomUUID(), "test@example.com");

        queryService.switchOrgResponse = new SwitchOrgResponse(
                targetOrgId, "Org Beta", Role.ADMIN, Set.of("product:manage", "inventory:view")
        );

        ResponseEntity<?> response = controller.switchOrganization(principal, new SwitchOrgRequest(targetOrgId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        SwitchOrgResponse body = (SwitchOrgResponse) response.getBody();
        assertThat(body.organizationId()).isEqualTo(targetOrgId);
        assertThat(body.role()).isEqualTo(Role.ADMIN);
        assertThat(body.permissions()).contains("product:manage");
    }

    @Test
    @DisplayName("POST /api/auth/switch-org returns 403 when user is not member of target organization")
    void switchOrgFailsForNonMember() {
        UUID userId = UUID.randomUUID();
        UUID targetOrgId = UUID.randomUUID();
        StockGuardPrincipal principal = new StockGuardPrincipal(userId, UUID.randomUUID(), "test@example.com");

        queryService.throwOnSwitch = new AccessDeniedException("User has no membership in organization");

        ResponseEntity<?> response = controller.switchOrganization(principal, new SwitchOrgRequest(targetOrgId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // --- Lightweight Stubs ---

    private static class StubAuthenticationService extends AuthenticationService {
        LoginResult nextLoginResult;
        RefreshResult nextRefreshResult;
        RuntimeException throwOnLogin;
        RuntimeException throwOnRefresh;
        String loggedOutToken;
        UUID revokedSessionId;

        StubAuthenticationService() {
            super(null, null, null, null, new SecurityProperties());
        }

        @Override
        public LoginResult login(LoginCommand command) {
            if (throwOnLogin != null) throw throwOnLogin;
            return nextLoginResult;
        }

        @Override
        public RefreshResult refresh(String rawRefreshToken) {
            if (throwOnRefresh != null) throw throwOnRefresh;
            return nextRefreshResult;
        }

        @Override
        public void logout(String rawRefreshToken) {
            this.loggedOutToken = rawRefreshToken;
        }

        @Override
        public void revokeSession(UUID sessionId, String reason) {
            this.revokedSessionId = sessionId;
        }
    }

    private static class StubUserContextQueryService extends UserContextQueryService {
        List<OrganizationSummaryDto> availableOrgs = Collections.emptyList();
        UserMeResponse userMeResponse;
        SwitchOrgResponse switchOrgResponse;
        RuntimeException throwOnSwitch;

        StubUserContextQueryService() {
            super(null, null, null);
        }

        @Override
        public List<OrganizationSummaryDto> getUserAvailableOrganizations(UUID userId) {
            return availableOrgs;
        }

        @Override
        public UserMeResponse getUserMe(UUID userId) {
            return userMeResponse;
        }

        @Override
        public SwitchOrgResponse switchOrganization(UUID userId, UUID targetOrgId) {
            if (throwOnSwitch != null) throw throwOnSwitch;
            return switchOrgResponse;
        }
    }
}
