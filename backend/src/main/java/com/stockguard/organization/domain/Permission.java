package com.stockguard.organization.domain;

/**
 * Granular application permissions per the approved role-permission matrix.
 * <p>
 * Strictly follows the canonical {@code <domain>:<action>} format.
 */
public enum Permission {
    DASHBOARD_VIEW("dashboard:view"),
    REPORTS_VIEW("reports:view"),
    INVENTORY_VIEW("inventory:view"),
    INVENTORY_RECEIVE("inventory:receive"),
    INVENTORY_ADJUST("inventory:adjust"),
    INVENTORY_RECONCILE("inventory:reconcile"),
    PRODUCT_VIEW("product:view"),
    PRODUCT_MANAGE("product:manage"),
    WAREHOUSE_VIEW("warehouse:view"),
    WAREHOUSE_MANAGE("warehouse:manage"),
    TRANSFER_VIEW("transfer:view"),
    TRANSFER_EXECUTE("transfer:execute"),
    ORDER_VIEW("order:view"),
    ORDER_CREATE("order:create"),
    ORDER_CONFIRM("order:confirm"),
    ORDER_FULFILL("order:fulfill"),
    ORDER_CANCEL("order:cancel"),
    RESERVATION_VIEW("reservation:view"),
    RESERVATION_CREATE("reservation:create"),
    RESERVATION_RELEASE("reservation:release"),
    MEMBERSHIP_VIEW("membership:view"),
    MEMBERSHIP_MANAGE("membership:manage"),
    AUDIT_VIEW("audit:view");

    private final String authority;

    Permission(String authority) {
        this.authority = authority;
    }

    public String getAuthority() {
        return authority;
    }

    @Override
    public String toString() {
        return authority;
    }
}
