package com.stockguard.security.filter;

import com.stockguard.security.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class CsrfProtectionFilterTest {

    private SecurityProperties securityProperties;
    private CsrfProtectionFilter filter;

    @BeforeEach
    void setUp() {
        securityProperties = new SecurityProperties();
        securityProperties.getCors().setAllowedOrigins(List.of("http://localhost:5173", "https://app.stockguard.com"));
        filter = new CsrfProtectionFilter(securityProperties);
    }

    @Test
    @DisplayName("Non-protected endpoints pass through without CSRF validation")
    void nonProtectedEndpointsPassThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/products");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain filterChain = (req, res) -> invoked.set(true);

        filter.doFilter(request, response, filterChain);

        assertThat(invoked.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("POST /api/auth/refresh without X-StockGuard-CSRF header is rejected with 403 Forbidden")
    void refreshWithoutCsrfHeaderIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
        request.addHeader("Origin", "http://localhost:5173");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain filterChain = (req, res) -> invoked.set(true);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains("Missing or invalid required CSRF header");
        assertThat(invoked.get()).isFalse();
    }

    @Test
    @DisplayName("POST /api/auth/refresh with invalid Origin header is rejected with 403 Forbidden")
    void refreshWithDisallowedOriginIsRejected() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
        request.addHeader("Origin", "https://malicious-site.com");
        request.addHeader("X-StockGuard-CSRF", "1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain filterChain = (req, res) -> invoked.set(true);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains("Origin not permitted");
        assertThat(invoked.get()).isFalse();
    }

    @Test
    @DisplayName("POST /api/auth/refresh with valid Origin and CSRF header passes through")
    void refreshWithValidOriginAndCsrfHeaderPasses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/refresh");
        request.addHeader("Origin", "http://localhost:5173");
        request.addHeader("X-StockGuard-CSRF", "1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain filterChain = (req, res) -> invoked.set(true);

        filter.doFilter(request, response, filterChain);

        assertThat(invoked.get()).isTrue();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("POST /api/auth/logout requires valid CSRF header")
    void logoutRequiresCsrfHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/logout");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean invoked = new AtomicBoolean(false);
        FilterChain filterChain = (req, res) -> invoked.set(true);

        filter.doFilter(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(response.getContentAsString()).contains("Missing or invalid required CSRF header");
        assertThat(invoked.get()).isFalse();
    }

    @Test
    @DisplayName("Wildcard origin is strictly rejected and never matched")
    void wildcardOriginIsRejected() {
        securityProperties.getCors().setAllowedOrigins(List.of("*", "http://localhost:5173"));
        assertThat(filter.isAllowedOrigin("*")).isFalse();
        assertThat(filter.isAllowedOrigin("https://evil.com")).isFalse();
        assertThat(filter.isAllowedOrigin("http://localhost:5173")).isTrue();
    }

    @Test
    @DisplayName("Malformed origin values fail closed")
    void malformedOriginFailsClosed() {
        assertThat(filter.isAllowedOrigin("null")).isFalse();
        assertThat(filter.isAllowedOrigin("http://localhost:5173/path")).isFalse();
        assertThat(filter.isAllowedOrigin("http://localhost:5173?query=1")).isFalse();
        assertThat(filter.isAllowedOrigin("http://user:pass@localhost:5173")).isFalse();
        assertThat(filter.isAllowedOrigin("not a uri")).isFalse();
        assertThat(filter.isAllowedOrigin("")).isFalse();
        assertThat(filter.isAllowedOrigin(null)).isFalse();
    }
}
