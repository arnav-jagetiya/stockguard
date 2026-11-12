package com.stockguard.security.exception;

/**
 * Thrown when an operation is attempted against an explicitly revoked session.
 */
public class SessionRevokedException extends RuntimeException {

    public SessionRevokedException(String message) {
        super(message);
    }
}
