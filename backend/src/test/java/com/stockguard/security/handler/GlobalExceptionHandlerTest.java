package com.stockguard.security.handler;

import com.stockguard.organization.exception.CannotDemoteLastAdminException;
import com.stockguard.security.exception.AuthenticationFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("MethodArgumentNotValidException maps to 400 Bad Request with field error details")
    void handleMethodArgumentNotValid() throws NoSuchMethodException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/register");
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new Object(), "target");
        bindingResult.addError(new FieldError("target", "email", "must not be blank"));

        MethodParameter parameter = new MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("handleMethodArgumentNotValid"),
                -1
        );
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, bindingResult);

        ResponseEntity<Map<String, Object>> response = handler.handleValidationException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(400);
        assertThat(response.getBody().get("error")).isEqualTo("Bad Request");
        assertThat(response.getBody().get("message")).toString().contains("Field 'email' must not be blank");
        assertThat(response.getBody().get("path")).isEqualTo("/api/auth/register");
    }

    @Test
    @DisplayName("HttpMessageNotReadableException maps to 400 Bad Request with sanitized message")
    void handleHttpMessageNotReadable() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error: syntax error",
                new MockHttpInputMessage("bad json".getBytes())
        );

        ResponseEntity<Map<String, Object>> response = handler.handleUnreadableMessage(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).isEqualTo("Malformed or unreadable JSON request body");
        assertThat(response.getBody().get("path")).isEqualTo("/api/auth/login");
    }

    @Test
    @DisplayName("CannotDemoteLastAdminException maps to 409 Conflict")
    void handleCannotDemoteLastAdmin() {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/organizations/1/members/2/role");
        CannotDemoteLastAdminException ex = new CannotDemoteLastAdminException(
                "Cannot change role: user is the sole remaining ADMIN of the organization."
        );

        ResponseEntity<Map<String, Object>> response = handler.handleCannotDemoteLastAdmin(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(409);
        assertThat(response.getBody().get("error")).isEqualTo("Conflict");
        assertThat(response.getBody().get("message")).toString().contains("sole remaining ADMIN");
    }

    @Test
    @DisplayName("AccessDeniedException maps to 403 Forbidden")
    void handleAccessDenied() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/organizations/1/members");
        AccessDeniedException ex = new AccessDeniedException("Access denied");

        ResponseEntity<Map<String, Object>> response = handler.handleAccessDenied(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(403);
        assertThat(response.getBody().get("error")).isEqualTo("Forbidden");
        assertThat(response.getBody().get("message")).isEqualTo("Access denied: insufficient permissions");
    }

    @Test
    @DisplayName("AuthenticationFailedException maps to 401 Unauthorized")
    void handleAuthenticationFailed() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        AuthenticationFailedException ex = new AuthenticationFailedException("Invalid credentials provided.");

        ResponseEntity<Map<String, Object>> response = handler.handleAuthenticationFailed(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(401);
        assertThat(response.getBody().get("error")).isEqualTo("Unauthorized");
        assertThat(response.getBody().get("message")).isEqualTo("Invalid credentials provided.");
    }

    @Test
    @DisplayName("Generic unhandled Exception maps to 500 Internal Server Error without leaking internal stack trace")
    void handleGenericException() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/internal/test");
        RuntimeException ex = new RuntimeException("DB Connection Timeout: secret-credentials-leaked");

        ResponseEntity<Map<String, Object>> response = handler.handleUnhandledException(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("status")).isEqualTo(500);
        assertThat(response.getBody().get("error")).isEqualTo("Internal Server Error");
        assertThat(response.getBody().get("message")).isEqualTo("An unexpected internal error occurred. Please contact system support.");
        assertThat(response.getBody().get("path")).isEqualTo("/api/internal/test");
    }
}
