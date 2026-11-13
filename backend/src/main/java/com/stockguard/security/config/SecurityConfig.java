package com.stockguard.security.config;

import com.stockguard.security.filter.CsrfProtectionFilter;
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
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
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
 *   <li>Consistent JSON error responses for 401 Unauthorized and 403 Forbidden</li>
 *   <li>Method-level authorization via {@link EnableMethodSecurity}</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    private final SecurityProperties securityProperties;
    private final JwtService jwtService;
    private final DatabaseSessionValidator databaseSessionValidator;
    private final TenantMembershipValidator tenantMembershipValidator;
    private final SecurityJsonAuthenticationEntryPoint authenticationEntryPoint;
    private final SecurityJsonAccessDeniedHandler accessDeniedHandler;

    public SecurityConfig(
            SecurityProperties securityProperties,
            JwtService jwtService,
            DatabaseSessionValidator databaseSessionValidator,
            TenantMembershipValidator tenantMembershipValidator,
            SecurityJsonAuthenticationEntryPoint authenticationEntryPoint,
            SecurityJsonAccessDeniedHandler accessDeniedHandler
    ) {
        this.securityProperties = securityProperties;
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

        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        // Public operational endpoints
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Public authentication endpoints
                        .requestMatchers("/api/auth/login", "/api/auth/refresh").permitAll()
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
                .addFilterAfter(organizationContextFilter, JwtAuthenticationFilter.class);

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
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("Direct UserDetailsService authentication is disabled in favor of JWT authentication");
        };
    }
}
