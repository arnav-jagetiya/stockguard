package com.stockguard.security.handler;

import com.stockguard.audit.service.AuditLogService;
import com.stockguard.security.context.OrganizationContextHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final Logger log = LoggerFactory.getLogger(SecurityJsonAccessDeniedHandler.class);

    private final AuditLogService auditLogService;

    @Autowired
    public SecurityJsonAccessDeniedHandler(ObjectProvider<AuditLogService> auditLogServiceProvider) {
        this.auditLogService = auditLogServiceProvider != null ? auditLogServiceProvider.getIfAvailable() : null;
    }

    public SecurityJsonAccessDeniedHandler() {
        this.auditLogService = null;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException {
        String customMessage = (String) request.getAttribute("stockguard.access.error_message");
        String message = customMessage != null ? customMessage : "Access denied: insufficient permissions";

        OrganizationContextHolder.getContext().ifPresent(ctx -> {
            try {
                if (auditLogService != null) {
                    auditLogService.logAccessDenied(
                            ctx.organizationId(),
                            ctx.userId(),
                            request.getMethod() + " " + request.getRequestURI(),
                            message
                    );
                }
            } catch (Exception auditEx) {
                log.warn("Failed to record access denied audit event in handler: {}", auditEx.getMessage());
            }
        });

        SecurityErrorResponseUtils.writeJsonError(
                response,
                HttpStatus.FORBIDDEN.value(),
                "Forbidden",
                message,
                request.getRequestURI()
        );
    }
}
