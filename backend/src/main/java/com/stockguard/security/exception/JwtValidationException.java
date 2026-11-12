package com.stockguard.security.exception;

/**
 * Thrown when a JWT token fails cryptographic signature verification, structure validation,
 * expiration checks, or claim assertions.
 */
public class JwtValidationException extends RuntimeException {

    public JwtValidationException(String message) {
        super(message);
    }

    public JwtValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
