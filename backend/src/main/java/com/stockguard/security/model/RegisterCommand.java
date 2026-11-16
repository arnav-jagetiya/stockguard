package com.stockguard.security.model;

/**
 * Internal command holding validated registration data and client network context.
 */
public record RegisterCommand(
        String fullName,
        String email,
        String password,
        String organizationName,
        String ipAddress,
        String userAgent
) {}
