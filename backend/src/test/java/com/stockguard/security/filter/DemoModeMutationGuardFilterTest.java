package com.stockguard.security.filter;

import com.stockguard.security.config.SecurityProperties;
import com.stockguard.security.model.StockGuardPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class DemoModeMutationGuardFilterTest {

    private SecurityProperties securityProperties;
    private DemoModeMutationGuardFilter filter;

    @BeforeEach
    void setUp() {
        securityProperties = new SecurityProperties();
        securityProperties.getPublicDemo().setEnabled(true);
        securityProperties.getPublicDemo().setDemoEmail("demo.visitor@stockguard.dev");
        filter = new DemoModeMutationGuardFilter(securityProperties);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateUser(String email) {
        StockGuardPrincipal principal = new StockGuardPrincipal(
                UUID.randomUUID(),
                UUID.randomUUID(),
                email
        );
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_VIEWER"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("GET requests for demo visitor are permitted through the filter")
    void getRequestsForDemoVisitorPermitted() throws Exception {
        authenticateUser("demo.visitor@stockguard.dev");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passedDownstream = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> passedDownstream.set(true);

        filter.doFilter(request, response, chain);

        assertThat(passedDownstream.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("POST /api/auth/refresh is exempted and permitted for demo visitor")
    void refreshExemptedForDemoVisitor() throws Exception {
        authenticateUser("demo.visitor@stockguard.dev");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passedDownstream = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> passedDownstream.set(true);

        filter.doFilter(request, response, chain);

        assertThat(passedDownstream.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("POST /api/auth/logout is exempted and permitted for demo visitor")
    void logoutExemptedForDemoVisitor() throws Exception {
        authenticateUser("demo.visitor@stockguard.dev");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/logout");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passedDownstream = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> passedDownstream.set(true);

        filter.doFilter(request, response, chain);

        assertThat(passedDownstream.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    @DisplayName("State mutations on business endpoints are blocked with 403 DEMO_MODE_MUTATION_PROHIBITED")
    void mutationsOnBusinessEndpointsBlocked(String method) throws Exception {
        authenticateUser("demo.visitor@stockguard.dev");

        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passedDownstream = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> passedDownstream.set(true);

        filter.doFilter(request, response, chain);

        assertThat(passedDownstream.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        String body = response.getContentAsString();
        assertThat(body).contains("DEMO_MODE_MUTATION_PROHIBITED");
        assertThat(body).contains("Demo visitor sessions are read-only");
    }

    @Test
    @DisplayName("Non-demo users are not blocked from mutations")
    void nonDemoUsersAllowedMutations() throws Exception {
        authenticateUser("admin@stockguard.dev");

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passedDownstream = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> passedDownstream.set(true);

        filter.doFilter(request, response, chain);

        assertThat(passedDownstream.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("Unauthenticated requests pass through to downstream security filters")
    void unauthenticatedRequestsPassThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean passedDownstream = new AtomicBoolean(false);
        FilterChain chain = (req, res) -> passedDownstream.set(true);

        filter.doFilter(request, response, chain);

        assertThat(passedDownstream.get()).isTrue();
    }
}
