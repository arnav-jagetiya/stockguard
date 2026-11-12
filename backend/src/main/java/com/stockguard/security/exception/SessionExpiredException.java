package com.stockguard.security.exception;

/**
 * Thrown when an operation is attempted against a session that has exceeded its absolute lifetime.
 */
public class SessionExpiredException extends RuntimeException {

    public SessionExpiredException(String message) {
        super(message);
    }
}
