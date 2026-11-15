package com.stockguard.inventory.domain;

/**
 * Lifecycle states of an inventory reconciliation discrepancy per ADR-012.
 */
public enum DiscrepancyStatus {
    OPEN,
    ACKNOWLEDGED,
    INVESTIGATING,
    RESOLVED,
    REJECTED;

    public boolean isActive() {
        return this == OPEN || this == ACKNOWLEDGED || this == INVESTIGATING;
    }

    public boolean isTerminal() {
        return this == RESOLVED || this == REJECTED;
    }
}
