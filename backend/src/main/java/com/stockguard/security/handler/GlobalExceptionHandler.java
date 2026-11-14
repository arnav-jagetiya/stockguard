package com.stockguard.security.handler;

import com.stockguard.audit.service.AuditLogService;
import com.stockguard.order.exception.InvalidOrderStateException;
import com.stockguard.organization.exception.CannotDemoteLastAdminException;
import com.stockguard.security.context.OrganizationContextHolder;
import com.stockguard.security.exception.AuthenticationFailedException;
import com.stockguard.shared.exception.DuplicateResourceException;
import com.stockguard.shared.exception.InsufficientStockException;
import com.stockguard.shared.exception.InvalidOperationException;
import com.stockguard.shared.exception.ResourceNotFoundException;
import com.stockguard.shared.idempotency.IdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Centralized exception handler providing consistent, RFC-compliant JSON error payloads
 * across all REST controllers while preventing sensitive stack trace or database leakage.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AuditLogService auditLogService;

    @Autowired
    public GlobalExceptionHandler(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    public GlobalExceptionHandler() {
        this(null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        String validationErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));

        String message = validationErrors.isBlank() ? "Validation failed for request payload" : validationErrors;

        return buildErrorResponse(HttpStatus.BAD_REQUEST, "Bad Request", message, request.getRequestURI());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableMessage(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        log.warn("Malformed HTTP request body for URI {}: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                "Malformed or unreadable JSON request body",
                request.getRequestURI()
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                ex.getMessage() != null ? ex.getMessage() : "Invalid request parameter",
                request.getRequestURI()
        );
    }

    @ExceptionHandler(CannotDemoteLastAdminException.class)
    public ResponseEntity<Map<String, Object>> handleCannotDemoteLastAdmin(
            CannotDemoteLastAdminException ex,
            HttpServletRequest request
    ) {
        log.warn("Last-admin protection triggered for URI {}: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "Conflict",
                ex.getMessage(),
                request.getRequestURI()
        );
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(
            IllegalStateException ex,
            HttpServletRequest request
    ) {
        log.warn("Illegal state encountered for URI {}: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "Conflict",
                ex.getMessage() != null ? ex.getMessage() : "Conflict with current resource state",
                request.getRequestURI()
        );
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(
            AccessDeniedException ex,
            HttpServletRequest request
    ) {
        log.warn("Access denied for URI {}: {}", request.getRequestURI(), ex.getMessage());

        OrganizationContextHolder.getContext().ifPresent(ctx -> {
            try {
                if (auditLogService != null) {
                    auditLogService.logAccessDenied(
                            ctx.organizationId(),
                            ctx.userId(),
                            request.getMethod() + " " + request.getRequestURI(),
                            ex.getMessage()
                    );
                }
            } catch (Exception auditEx) {
                log.warn("Failed to record access denied audit event: {}", auditEx.getMessage());
            }
        });

        return buildErrorResponse(
                HttpStatus.FORBIDDEN,
                "Forbidden",
                "Access denied: insufficient permissions",
                request.getRequestURI()
        );
    }

    @ExceptionHandler(AuthenticationFailedException.class)
    public ResponseEntity<Map<String, Object>> handleAuthenticationFailed(
            AuthenticationFailedException ex,
            HttpServletRequest request
    ) {
        log.warn("Authentication failed for URI {}: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(
                HttpStatus.UNAUTHORIZED,
                "Unauthorized",
                ex.getMessage(),
                request.getRequestURI()
        );
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleResourceNotFound(
            ResourceNotFoundException ex,
            HttpServletRequest request
    ) {
        log.warn("Resource not found for URI {}: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(
                HttpStatus.NOT_FOUND,
                "Not Found",
                ex.getMessage(),
                request.getRequestURI()
        );
    }

    @ExceptionHandler(DuplicateResourceException.class)
    public ResponseEntity<Map<String, Object>> handleDuplicateResource(
            DuplicateResourceException ex,
            HttpServletRequest request
    ) {
        log.warn("Duplicate resource conflict for URI {}: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "Conflict",
                ex.getMessage(),
                request.getRequestURI()
        );
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Map<String, Object>> handleIdempotencyConflict(
            IdempotencyConflictException ex,
            HttpServletRequest request
    ) {
        log.warn("Idempotency conflict for URI {}: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "Conflict",
                ex.getMessage(),
                request.getRequestURI()
        );
    }

    @ExceptionHandler(InsufficientStockException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientStock(
            InsufficientStockException ex,
            HttpServletRequest request
    ) {
        log.warn("Insufficient stock for URI {}: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "Conflict",
                ex.getMessage(),
                request.getRequestURI()
        );
    }

    @ExceptionHandler(InvalidOrderStateException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidOrderState(
            InvalidOrderStateException ex,
            HttpServletRequest request
    ) {
        log.warn("Invalid order state transition for URI {}: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(
                HttpStatus.CONFLICT,
                "Conflict",
                ex.getMessage(),
                request.getRequestURI()
        );
    }

    @ExceptionHandler(InvalidOperationException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidOperation(
            InvalidOperationException ex,
            HttpServletRequest request
    ) {
        log.warn("Invalid operation for URI {}: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                ex.getMessage(),
                request.getRequestURI()
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnhandledException(
            Exception ex,
            HttpServletRequest request
    ) {
        log.error("Unhandled internal server error for URI {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildErrorResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "An unexpected internal error occurred. Please contact system support.",
                request.getRequestURI()
        );
    }

    private String formatFieldError(FieldError error) {
        return "Field '" + error.getField() + "' " + (error.getDefaultMessage() != null ? error.getDefaultMessage() : "is invalid");
    }

    private ResponseEntity<Map<String, Object>> buildErrorResponse(
            HttpStatus status,
            String error,
            String message,
            String path
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        body.put("path", path);
        return ResponseEntity.status(status).body(body);
    }
}
