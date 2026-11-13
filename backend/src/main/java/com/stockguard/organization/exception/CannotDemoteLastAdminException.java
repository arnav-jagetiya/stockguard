package com.stockguard.organization.exception;

/**
 * Thrown when an administrative action would demote, deactivate, or remove
 * the last remaining active administrator of an organization.
 */
public class CannotDemoteLastAdminException extends RuntimeException {

    public CannotDemoteLastAdminException(String message) {
        super(message);
    }
}
