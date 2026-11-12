package com.stockguard.security.exception;

/**
 * Thrown when user credentials or account state fails authentication.
 * <p>
 * Generic error messaging is deliberately used to prevent user enumeration.
 */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException(String message) {
        super(message);
    }
}
