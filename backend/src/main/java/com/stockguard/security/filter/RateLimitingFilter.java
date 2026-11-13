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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * In-memory sliding window rate limiting filter for authentication endpoints per ADR-010.
 * <p>
 * Enforces configurable per-minute request thresholds:
 * <ul>
 *   <li>{@code POST /api/auth/login}: {@link SecurityProperties.RateLimit#getLoginPermitsPerMinute()}</li>
 *   <li>{@code POST /api/auth/refresh}: {@link SecurityProperties.RateLimit#getRefreshPermitsPerMinute()}</li>
 * </ul>
 * <p>
 * <b>Architectural Notice & Multi-Instance Limitation:</b><br>
 * This single-instance in-memory rate limiter tracks request counts in JVM memory.
 * State resets on application restart and is not synchronized across multi-node clusters.
 * For production multi-node deployments, rate limiting should be coordinated at the reverse proxy
 * / API gateway tier (e.g. Envoy, NGINX, or Cloudflare).
 */
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String REFRESH_PATH = "/api/auth/refresh";
    private static final long WINDOW_MILLIS = 60_000L; // 1 minute sliding bucket

    private final SecurityProperties securityProperties;
    private final ConcurrentHashMap<String, WindowCounter> requestBuckets = new ConcurrentHashMap<>();

    public RateLimitingFilter(SecurityProperties securityProperties) {
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

        if ("POST".equalsIgnoreCase(method)) {
            int limit = 0;
            String endpointKey = null;

            if (LOGIN_PATH.equals(path)) {
                limit = securityProperties.getRateLimit().getLoginPermitsPerMinute();
                endpointKey = "login";
            } else if (REFRESH_PATH.equals(path)) {
                limit = securityProperties.getRateLimit().getRefreshPermitsPerMinute();
                endpointKey = "refresh";
            }

            if (endpointKey != null && limit > 0) {
                String clientIp = resolveClientIp(request);
                String bucketKey = endpointKey + ":" + clientIp;

                if (!allowRequest(bucketKey, limit)) {
                    sendRateLimitError(response, request);
                    return;
                }
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean allowRequest(String key, int limit) {
        long now = System.currentTimeMillis();
        long windowStart = now - (now % WINDOW_MILLIS);

        WindowCounter counter = requestBuckets.compute(key, (k, existing) -> {
            if (existing == null || existing.windowStart != windowStart) {
                return new WindowCounter(windowStart, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });

        // Periodic maintenance cleanup
        if (requestBuckets.size() > 5000) {
            cleanStaleBuckets(now);
        }

        return counter.count.get() <= limit;
    }

    private void cleanStaleBuckets(long now) {
        long threshold = now - (WINDOW_MILLIS * 2);
        requestBuckets.entrySet().removeIf(entry -> entry.getValue().windowStart < threshold);
    }

    private static final Pattern IPV4_PATTERN = Pattern.compile(
            "^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)$"
    );
    private static final Pattern IPV6_PATTERN = Pattern.compile(
            "^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$|^(([0-9a-fA-F]{1,4}:){1,7}|:):((:[0-9a-fA-F]{1,4}){1,7}|:)$"
    );

    /**
     * Resolves the caller's IP address.
     * <p>
     * <b>Security Invariant:</b><br>
     * Defaults strictly to {@link HttpServletRequest#getRemoteAddr()}.
     * Never unconditionally trusts {@code X-Forwarded-For} headers from arbitrary callers.
     * <p>
     * Forwarded header inspection is only performed if:
     * <ol>
     *   <li>{@link SecurityProperties.RateLimit#getTrustedProxies()} is explicitly configured.</li>
     *   <li>{@code request.getRemoteAddr()} matches one of the trusted proxy IPs.</li>
     * </ol>
     * If the immediate proxy is untrusted, or if the forwarded header is missing, blank, or malformed,
     * the resolver fails closed to {@code request.getRemoteAddr()}.
     */
    protected String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return "unknown";
        }

        List<String> trustedProxies = securityProperties.getRateLimit().getTrustedProxies();
        if (trustedProxies == null || trustedProxies.isEmpty()) {
            return remoteAddr;
        }

        if (!trustedProxies.contains(remoteAddr.trim())) {
            return remoteAddr;
        }

        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddr;
        }

        String clientIp = forwardedFor.split(",")[0].trim();
        if (isValidIp(clientIp)) {
            return clientIp;
        }

        return remoteAddr;
    }

    private boolean isValidIp(String ip) {
        if (ip == null || ip.isBlank() || ip.length() > 45) {
            return false;
        }
        return IPV4_PATTERN.matcher(ip).matches() || IPV6_PATTERN.matcher(ip).matches();
    }

    private void sendRateLimitError(HttpServletResponse response, HttpServletRequest request) throws IOException {
        response.setHeader("Retry-After", "60");
        SecurityErrorResponseUtils.writeJsonError(
                response,
                HttpStatus.TOO_MANY_REQUESTS.value(),
                "Too Many Requests",
                "Too many authentication requests. Please try again later.",
                request.getRequestURI()
        );
    }

    private record WindowCounter(long windowStart, AtomicInteger count) {}
}
