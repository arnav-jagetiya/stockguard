package com.stockguard.security.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Payload submitted to {@code POST /api/auth/register} per Option A (Organization Registration).
 */
public record RegisterRequest(
        @NotBlank(message = "Full name must not be blank")
        @Size(min = 2, max = 100, message = "Full name must be between 2 and 100 characters")
        String fullName,

        @NotBlank(message = "Email must not be blank")
        @Email(message = "Email must be a valid email format")
        String email,

        @NotBlank(message = "Password must not be blank")
        @Size(min = 8, max = 100, message = "Password must be at least 8 characters")
        String password,

        @NotBlank(message = "Organization name must not be blank")
        @Size(min = 2, max = 100, message = "Organization name must be between 2 and 100 characters")
        String organizationName
) {
    @Override
    public String toString() {
        return "RegisterRequest{" +
                "fullName='" + fullName + '\'' +
                ", email='" + email + '\'' +
                ", organizationName='" + organizationName + '\'' +
                ", password=[PROTECTED]" +
                '}';
    }
}
