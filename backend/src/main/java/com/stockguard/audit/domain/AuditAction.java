package com.stockguard.audit.domain;

/**
 * Standardized audit action constants for the STOCKGUARD platform.
 */
public final class AuditAction {

    // Inventory Domain
    public static final String STOCK_ADJUSTED = "STOCK_ADJUSTED";
    public static final String OPENING_BALANCE_RECORDED = "OPENING_BALANCE_RECORDED";
    public static final String STOCK_RECEIVED = "STOCK_RECEIVED";
    public static final String TRANSFER_COMPLETED = "TRANSFER_COMPLETED";

    // Order Domain
    public static final String ORDER_CREATED = "ORDER_CREATED";
    public static final String ORDER_CONFIRMED = "ORDER_CONFIRMED";
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";
    public static final String ORDER_FULFILLED = "ORDER_FULFILLED";

    // Reservation Domain
    public static final String RESERVATION_CREATED = "RESERVATION_CREATED";
    public static final String RESERVATION_RELEASED = "RESERVATION_RELEASED";
    public static final String RESERVATION_EXPIRED = "RESERVATION_EXPIRED";
    public static final String RESERVATION_CONSUMED = "RESERVATION_CONSUMED";

    // Organization & Membership Domain
    public static final String MEMBERSHIP_CREATED = "MEMBERSHIP_CREATED";
    public static final String MEMBERSHIP_ROLE_UPDATED = "MEMBERSHIP_ROLE_UPDATED";
    public static final String MEMBERSHIP_DEACTIVATED = "MEMBERSHIP_DEACTIVATED";

    // Security & Session Domain
    public static final String SESSION_REVOKED = "SESSION_REVOKED";
    public static final String REFRESH_TOKEN_REPLAY_DETECTED = "REFRESH_TOKEN_REPLAY_DETECTED";
    public static final String ORGANIZATION_SWITCHED = "ORGANIZATION_SWITCHED";
    public static final String ACCESS_DENIED = "ACCESS_DENIED";

    // Operational Reconciliation Domain
    public static final String RECONCILIATION_DISCREPANCY_DETECTED = "RECONCILIATION_DISCREPANCY_DETECTED";
    public static final String RECONCILIATION_COMPLETED = "RECONCILIATION_COMPLETED";

    private AuditAction() {
    }
}
