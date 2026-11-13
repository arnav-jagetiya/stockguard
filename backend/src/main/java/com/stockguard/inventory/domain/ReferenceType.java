package com.stockguard.inventory.domain;

/**
 * Permitted external reference types matching check constraint
 * {@code chk_inventory_transactions_reference_pair}.
 */
public enum ReferenceType {
    ORDER,
    TRANSFER,
    MANUAL
}
