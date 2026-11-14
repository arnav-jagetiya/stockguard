package com.stockguard.audit.domain;

/**
 * Standardized audit entity type constants.
 */
public final class AuditEntityType {

    public static final String INVENTORY_BALANCE = "INVENTORY_BALANCE";
    public static final String INVENTORY_TRANSACTION = "INVENTORY_TRANSACTION";
    public static final String ORDER = "ORDER";
    public static final String RESERVATION = "RESERVATION";
    public static final String TRANSFER = "TRANSFER";
    public static final String ORGANIZATION_MEMBERSHIP = "ORGANIZATION_MEMBERSHIP";
    public static final String AUTH_SESSION = "AUTH_SESSION";
    public static final String RECONCILIATION = "RECONCILIATION";

    private AuditEntityType() {
    }
}
