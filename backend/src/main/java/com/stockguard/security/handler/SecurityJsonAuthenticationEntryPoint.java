package com.stockguard.security.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Custom authentication entry point returning standardized JSON error payloads for unauthenticated requests.
 * <p>
 * Suppresses HTTP Basic dialogs by deliberately omitting the {@code WWW-Authenticate: Basic} header.
 */
@Component
public class SecurityJsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException
    ) throws IOException {
        String customMessage = (String) request.getAttribute("stockguard.auth.error_message");
        String message = customMessage != null ? customMessage : "Authentication is required to access this resource";

        SecurityErrorResponseUtils.writeJsonError(
                response,
                HttpStatus.UNAUTHORIZED.value(),
                "Unauthorized",
                message,
                request.getRequestURI()
        );
    }
}
