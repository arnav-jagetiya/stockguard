package com.stockguard.order.exception;

/**
 * Thrown when an order operation attempts an invalid lifecycle state transition.
 * Maps to HTTP 409 Conflict.
 */
public class InvalidOrderStateException extends RuntimeException {

    public InvalidOrderStateException(String message) {
        super(message);
    }
}
