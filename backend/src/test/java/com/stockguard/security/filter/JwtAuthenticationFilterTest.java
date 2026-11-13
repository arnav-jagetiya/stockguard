package com.stockguard.security.filter;

import com.stockguard.security.config.SecurityProperties;
import com.stockguard.security.handler.SecurityJsonAuthenticationEntryPoint;
import com.stockguard.security.model.StockGuardAuthenticationToken;
import com.stockguard.security.model.StockGuardPrincipal;
import com.stockguard.security.service.DatabaseSessionValidator;
import com.stockguard.security.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class JwtAuthenticationFilterTest {

    private JwtService jwtService;
    private AtomicReference<DatabaseSessionValidator.SessionValidationResult> sessionResultRef;
    private DatabaseSessionValidator sessionValidator;
    private SecurityJsonAuthenticationEntryPoint entryPoint;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();

        SecurityProperties props = new SecurityProperties();
        props.getJwt().getActiveKey().setId("sg-test-active");
        props.getJwt().getActiveKey().setSecret("dGVzdC1zZWNyZXQtMzItYnl0ZXMtY3J5cHRvLWtleS0yMDI2IQ==");
        props.getJwt().setIssuer("stockguard-platform");
        props.getJwt().setAudience("stockguard-api");

        this.jwtService = new JwtService(props);
        this.sessionResultRef = new AtomicReference<>();
        this.sessionValidator = (sessionId, userId) -> {
            DatabaseSessionValidator.SessionValidationResult res = sessionResultRef.get();
            return res != null ? res : DatabaseSessionValidator.SessionValidationResult.valid(sessionId, userId);
        };
        this.entryPoint = new SecurityJsonAuthenticationEntryPoint();
        this.filter = new JwtAuthenticationFilter(jwtService, sessionValidator, entryPoint);
    }

    @Test
    @DisplayName("Request without Authorization header passes through without setting authentication")
    void requestWithoutAuthHeaderPassesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain filterChain = (req, res) -> invoked.set(true);

        filter.doFilter(request, response, filterChain);

        assertThat(invoked.get()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Request with blank Bearer token returns 401 Unauthorized")
    void requestWithBlankBearerTokenReturns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader("Authorization", "Bearer   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain filterChain = (req, res) -> invoked.set(true);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentAsString()).contains("Unauthorized");
        assertThat(invoked.get()).isFalse();
    }

    @Test
    @DisplayName("Request with valid JWT and active session sets SecurityContext")
    void validJwtAndActiveSessionSetsSecurityContext() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String email = "test@example.com";
        String token = jwtService.generateAccessToken(userId, email, sessionId);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain filterChain = (req, res) -> invoked.set(true);

        filter.doFilter(request, response, filterChain);

        assertThat(invoked.get()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isInstanceOf(StockGuardAuthenticationToken.class);

        StockGuardPrincipal principal = (StockGuardPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.sessionId()).isEqualTo(sessionId);
        assertThat(principal.email()).isEqualTo(email);
    }

    @Test
    @DisplayName("Request with invalid or forged JWT signature returns 401 Unauthorized")
    void invalidJwtReturns401() throws Exception {
        String token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6InNnLXRlc3QtYWN0aXZlIn0." +
                "eyJzdWIiOiIxMTExMTExMS0xMTExLTExMTEtMTExMS0xMTExMTExMTExMTEifQ." +
                "invalidsignatureinvalidsignature12345";

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain filterChain = (req, res) -> invoked.set(true);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentAsString()).contains("Unauthorized");
        assertThat(invoked.get()).isFalse();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Request with revoked session in database returns 401 Unauthorized")
    void revokedSessionReturns401() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId, "user@example.com", sessionId);

        sessionResultRef.set(DatabaseSessionValidator.SessionValidationResult.invalid(
                DatabaseSessionValidator.Status.SESSION_REVOKED,
                sessionId, userId, "Authentication session has been revoked"
        ));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain filterChain = (req, res) -> invoked.set(true);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentAsString()).contains("Authentication session has been revoked");
        assertThat(invoked.get()).isFalse();
    }

    @Test
    @DisplayName("Request with inactive user account returns 401 Unauthorized")
    void inactiveUserAccountReturns401() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId, "inactive@example.com", sessionId);

        sessionResultRef.set(DatabaseSessionValidator.SessionValidationResult.invalid(
                DatabaseSessionValidator.Status.USER_INACTIVE,
                sessionId, userId, "User account is inactive"
        ));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain filterChain = (req, res) -> invoked.set(true);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentAsString()).contains("User account is inactive");
        assertThat(invoked.get()).isFalse();
    }

    @Test
    @DisplayName("Database session validation failure fails closed with 401 Unauthorized")
    void databaseValidationExceptionFailsClosed() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String token = jwtService.generateAccessToken(userId, "user@example.com", sessionId);

        // Session validator throws exception
        this.sessionValidator = (sid, uid) -> {
            throw new org.springframework.dao.DataAccessResourceFailureException("PostgreSQL connection failure");
        };
        this.filter = new JwtAuthenticationFilter(jwtService, sessionValidator, entryPoint);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain filterChain = (req, res) -> invoked.set(true);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        assertThat(response.getContentAsString()).contains("Authentication service temporarily unavailable");
        assertThat(invoked.get()).isFalse();
    }
}
