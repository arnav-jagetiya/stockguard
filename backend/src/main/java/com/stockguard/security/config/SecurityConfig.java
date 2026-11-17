package com.stockguard.security.config;

import com.stockguard.infrastructure.metrics.MonitoringProperties;
import com.stockguard.security.filter.CsrfProtectionFilter;
import com.stockguard.security.filter.DemoModeMutationGuardFilter;
import com.stockguard.security.filter.JwtAuthenticationFilter;
import com.stockguard.security.filter.OrganizationContextFilter;
import com.stockguard.security.filter.RateLimitingFilter;
import com.stockguard.security.handler.SecurityJsonAccessDeniedHandler;
import com.stockguard.security.handler.SecurityJsonAuthenticationEntryPoint;
import com.stockguard.security.service.DatabaseSessionValidator;
import com.stockguard.security.service.JwtService;
import com.stockguard.security.service.TenantMembershipValidator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Production Spring Security configuration for STOCKGUARD.
 * <p>
 * Configures:
 * <ul>
 *   <li>Stateless session creation policy</li>
 *   <li>Strict CORS policy matching configured allowed origins</li>
 *   <li>In-memory rate limiting for authentication endpoints</li>
 *   <li>Origin and custom-header CSRF protection for cookie-bound endpoints</li>
 *   <li>Bearer JWT authentication filter and authoritative PostgreSQL session verification</li>
 *   <li>Authoritative PostgreSQL organization context and membership filter</li>
 *   <li>HTTP Basic authentication for Prometheus metric scraper with {@code ROLE_MONITORING} per ADR-014</li>
 *   <li>Consistent JSON error responses for 401 Unauthorized and 403 Forbidden</li>
 *   <li>Method-level authorization via {@link EnableMethodSecurity}</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@EnableConfigurationProperties({SecurityProperties.class, MonitoringProperties.class})
public class SecurityConfig {

    private final SecurityProperties securityProperties;
    private final MonitoringProperties monitoringProperties;
    private final JwtService jwtService;
    private final DatabaseSessionValidator databaseSessionValidator;
    private final TenantMembershipValidator tenantMembershipValidator;
    private final SecurityJsonAuthenticationEntryPoint authenticationEntryPoint;
    private final SecurityJsonAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            SecurityProperties securityProperties,
            MonitoringProperties monitoringProperties,
            JwtService jwtService,
            DatabaseSessionValidator databaseSessionValidator,
            TenantMembershipValidator tenantMembershipValidator,
            SecurityJsonAuthenticationEntryPoint authenticationEntryPoint,
            SecurityJsonAccessDeniedHandler accessDeniedHandler
    ) {
        this.securityProperties = securityProperties;
        this.monitoringProperties = monitoringProperties;
        this.jwtService = jwtService;
        this.databaseSessionValidator = databaseSessionValidator;
        this.tenantMembershipValidator = tenantMembershipValidator;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        RateLimitingFilter rateLimitingFilter = new RateLimitingFilter(securityProperties);
        CsrfProtectionFilter csrfProtectionFilter = new CsrfProtectionFilter(securityProperties);
        JwtAuthenticationFilter jwtAuthenticationFilter = new JwtAuthenticationFilter(
                jwtService,
                databaseSessionValidator,
                authenticationEntryPoint
        );
        OrganizationContextFilter organizationContextFilter = new OrganizationContextFilter(tenantMembershipValidator);
        DemoModeMutationGuardFilter demoModeMutationGuardFilter = new DemoModeMutationGuardFilter(securityProperties);

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(basic -> basic.authenticationEntryPoint(authenticationEntryPoint))
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        // Public operational health and readiness endpoints
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Prometheus scrape endpoint requires machine-to-machine ROLE_MONITORING authority
                        .requestMatchers("/actuator/prometheus").hasAuthority("ROLE_MONITORING")
                        // Public authentication endpoints
                        .requestMatchers("/api/auth/login", "/api/auth/refresh", "/api/auth/register", "/api/auth/demo-credentials", "/api/auth/demo-login").permitAll()
                        // User-scoped authenticated endpoints
                        .requestMatchers("/api/auth/logout", "/api/auth/me", "/api/auth/switch-org").authenticated()
                        // All other application routes require authentication by default
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll()
                )
                // Filter ordering
                .addFilterBefore(rateLimitingFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(csrfProtectionFilter, RateLimitingFilter.class)
                .addFilterAfter(jwtAuthenticationFilter, CsrfProtectionFilter.class)
                .addFilterAfter(organizationContextFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(demoModeMutationGuardFilter, OrganizationContextFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> allowedOrigins = securityProperties.getCors().getAllowedOrigins();
        if (allowedOrigins != null && !allowedOrigins.isEmpty()) {
            config.setAllowedOrigins(allowedOrigins);
        } else {
            config.setAllowedOrigins(List.of("http://localhost:5173"));
        }

        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "X-Organization-Id",
                "X-StockGuard-CSRF",
                "Idempotency-Key",
                "Origin",
                "Accept"
        ));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder passwordEncoder) {
        String scraperUser = monitoringProperties.getScraperUsername();
        String scraperPass = monitoringProperties.getScraperPassword();
        String encodedPass = passwordEncoder.encode(scraperPass);

        UserDetails monitoringUser = org.springframework.security.core.userdetails.User
                .withUsername(scraperUser)
                .password(encodedPass)
                .authorities("ROLE_MONITORING")
                .build();

        return username -> {
            if (scraperUser != null && scraperUser.equals(username)) {
                return monitoringUser;
            }
            throw new UsernameNotFoundException("User not found: " + username);
        };
    }
}
