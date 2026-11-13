package com.stockguard.security.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Custom access denied handler returning standardized JSON error payloads for unauthorized requests.
 */
@Component
public class SecurityJsonAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        String customMessage = (String) request.getAttribute("stockguard.access.error_message");
        String message = customMessage != null ? customMessage : "Access denied: insufficient permissions";

        SecurityErrorResponseUtils.writeJsonError(
                response,
                HttpStatus.FORBIDDEN.value(),
                "Forbidden",
                message,
                request.getRequestURI()
        );
    }
}
