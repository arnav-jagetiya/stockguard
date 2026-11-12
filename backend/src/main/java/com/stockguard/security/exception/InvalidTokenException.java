package com.stockguard.security.exception;

/**
 * Thrown when a presented refresh token is not found or has an invalid structure.
 */
public class InvalidTokenException extends RuntimeException {

    public InvalidTokenException(String message) {
        super(message);
    }
}
