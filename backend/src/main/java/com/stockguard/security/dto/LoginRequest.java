package com.stockguard.security.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Payload submitted to {@code POST /api/auth/login}.
 */
public record LoginRequest(
        @NotBlank(message = "Email must not be blank")
        @Email(message = "Email must be a valid email format")
        String email,

        @NotBlank(message = "Password must not be blank")
        String password
) {
    @Override
    public String toString() {
        return "LoginRequest{" +
                "email='" + email + '\'' +
                ", password=[PROTECTED]" +
                '}';
    }
}
