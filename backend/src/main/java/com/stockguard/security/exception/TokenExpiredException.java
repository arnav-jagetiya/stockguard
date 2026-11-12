package com.stockguard.security.exception;

/**
 * Thrown when a presented refresh token has exceeded its validity window.
 */
public class TokenExpiredException extends RuntimeException {

    public TokenExpiredException(String message) {
        super(message);
    }
}
