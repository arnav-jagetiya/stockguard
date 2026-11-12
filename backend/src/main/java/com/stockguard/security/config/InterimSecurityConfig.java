package com.stockguard.security.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.Http403ForbiddenEntryPoint;

/**
 * Interim Spring Security configuration for Milestone 4, Slice 1.
 * <p>
 * PURPOSE AND LIFECYCLE:
 * <ul>
 *   <li>Suppresses Spring Boot's default auto-generated user credentials and default HTTP Basic dialogs.</li>
 *   <li>Establishes a deliberate, fail-closed posture: Actuator health and info endpoints are permitted,
 *       while all application API routes are denied until Slice 4 introduces the production filter chain.</li>
 *   <li>Enforces a strictly stateless session policy ({@link SessionCreationPolicy#STATELESS}).</li>
 *   <li>Enables {@link SecurityProperties} configuration property binding.</li>
 * </ul>
 * <p>
 * This interim configuration will be superseded by the complete {@code SecurityConfig} in Slice 4.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class InterimSecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .anyRequest().denyAll()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(new Http403ForbiddenEntryPoint())
                );

        return http.build();
    }

    /**
     * Disabled UserDetailsService preventing Spring Boot from auto-generating an ephemeral
     * default security password during startup while maintaining a strict fail-closed posture.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException(
                    "User authentication is not supported by interim security configuration");
        };
    }
}
