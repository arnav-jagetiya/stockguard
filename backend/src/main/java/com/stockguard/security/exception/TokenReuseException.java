package com.stockguard.security.exception;

/**
 * Thrown when an already-consumed refresh token is presented for rotation.
 * <p>
 * Indicates potential token theft; triggers immediate, committed revocation of the entire session family.
 */
public class TokenReuseException extends RuntimeException {

    public TokenReuseException(String message) {
        super(message);
    }
}
