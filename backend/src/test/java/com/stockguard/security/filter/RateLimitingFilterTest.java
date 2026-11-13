package com.stockguard.security.filter;

import com.stockguard.security.config.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitingFilterTest {

    private SecurityProperties securityProperties;
    private RateLimitingFilter filter;

    @BeforeEach
    void setUp() {
        securityProperties = new SecurityProperties();
        securityProperties.getRateLimit().setLoginPermitsPerMinute(3);
        securityProperties.getRateLimit().setRefreshPermitsPerMinute(5);
        filter = new RateLimitingFilter(securityProperties);
    }

    @Test
    @DisplayName("Requests below threshold are permitted")
    void requestsBelowThresholdPermitted() throws Exception {
        AtomicInteger invocationCount = new AtomicInteger(0);
        FilterChain filterChain = (req, res) -> invocationCount.incrementAndGet();

        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
            request.setRemoteAddr("192.168.1.100");
            MockHttpServletResponse response = new MockHttpServletResponse();

            filter.doFilter(request, response, filterChain);
            assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        }

        assertThat(invocationCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("Requests exceeding threshold receive 429 Too Many Requests with Retry-After header")
    void requestsExceedingThresholdReceive429() throws Exception {
        AtomicInteger invocationCount = new AtomicInteger(0);
        FilterChain filterChain = (req, res) -> invocationCount.incrementAndGet();

        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
            request.setRemoteAddr("10.0.0.1");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, filterChain);
        }

        // 4th request exceeds threshold (limit: 3)
        MockHttpServletRequest excessiveRequest = new MockHttpServletRequest("POST", "/api/auth/login");
        excessiveRequest.setRemoteAddr("10.0.0.1");
        MockHttpServletResponse excessiveResponse = new MockHttpServletResponse();

        filter.doFilter(excessiveRequest, excessiveResponse, filterChain);

        assertThat(excessiveResponse.getStatus()).isEqualTo(429);
        assertThat(excessiveResponse.getHeader("Retry-After")).isEqualTo("60");
        assertThat(excessiveResponse.getContentAsString()).contains("Too Many Requests");
        assertThat(invocationCount.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("Different IP addresses have distinct independent rate limit buckets")
    void differentIpsHaveDistinctBuckets() throws Exception {
        AtomicInteger invocationCount = new AtomicInteger(0);
        FilterChain filterChain = (req, res) -> invocationCount.incrementAndGet();

        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
            request.setRemoteAddr("10.0.0.2");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, filterChain);
        }

        // Distinct IP should still be allowed
        MockHttpServletRequest distinctIpRequest = new MockHttpServletRequest("POST", "/api/auth/login");
        distinctIpRequest.setRemoteAddr("10.0.0.3");
        MockHttpServletResponse distinctResponse = new MockHttpServletResponse();

        filter.doFilter(distinctIpRequest, distinctResponse, filterChain);
        assertThat(distinctResponse.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(invocationCount.get()).isEqualTo(4);
    }

    @Test
    @DisplayName("Untrusted client cannot spoof IP via X-Forwarded-For when trusted proxies are not configured")
    void untrustedClientCannotSpoofIpWithoutTrustedProxies() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("203.0.113.10");
        request.addHeader("X-Forwarded-For", "198.51.100.5");

        String resolved = filter.resolveClientIp(request);
        assertThat(resolved).isEqualTo("203.0.113.10");
    }

    @Test
    @DisplayName("Trusted proxy allows client IP resolution from X-Forwarded-For")
    void trustedProxyResolvesClientIpFromForwardedHeader() {
        securityProperties.getRateLimit().setTrustedProxies(java.util.List.of("10.0.0.1", "127.0.0.1"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("10.0.0.1"); // Matches trusted proxy
        request.addHeader("X-Forwarded-For", "198.51.100.5, 10.0.0.1");

        String resolved = filter.resolveClientIp(request);
        assertThat(resolved).isEqualTo("198.51.100.5");
    }

    @Test
    @DisplayName("Malformed X-Forwarded-For IP fails closed to remote address")
    void malformedForwardedIpFailsClosedToRemoteAddress() {
        securityProperties.getRateLimit().setTrustedProxies(java.util.List.of("10.0.0.1"));

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("10.0.0.1");
        request.addHeader("X-Forwarded-For", "invalid<script>alert(1)</script>, 10.0.0.1");

        String resolved = filter.resolveClientIp(request);
        assertThat(resolved).isEqualTo("10.0.0.1");
    }
}
