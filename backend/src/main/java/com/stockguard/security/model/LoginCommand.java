package com.stockguard.security.model;

/**
 * Command object encapsulating credentials and client metadata for login authentication.
 */
public record LoginCommand(
        String email,
        String password,
        String ipAddress,
        String userAgent
) {
    public LoginCommand {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email must not be blank");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password must not be blank");
        }
    }

    @Override
    public String toString() {
        return "LoginCommand{" +
                "email='" + email + '\'' +
                ", password=[PROTECTED]" +
                ", ipAddress='" + ipAddress + '\'' +
                ", userAgent='" + userAgent + '\'' +
                '}';
    }
}
