package com.stockguard.inventory.domain;

/**
 * Permitted inventory transaction types matching check constraint
 * {@code chk_inventory_transactions_type}.
 */
public enum TransactionType {
    OPENING_BALANCE,
    RECEIPT,
    ADJUSTMENT,
    RESERVATION,
    RELEASE,
    FULFILLMENT,
    TRANSFER
}
