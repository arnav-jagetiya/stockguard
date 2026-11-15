package com.stockguard.inventory.domain;

/**
 * Standardized discrepancy classification types per ADR-012.
 */
public enum DiscrepancyType {
    DELTA_MISMATCH,
    MISSING_BALANCE_ROW,
    MISSING_LEDGER_ENTRIES
}
