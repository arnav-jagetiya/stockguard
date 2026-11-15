package com.stockguard.inventory.dto;

/**
 * Result summary of a complete multi-tenant scheduled reconciliation sweep.
 */
public record ReconciliationSweepResult(
        int organizationsAttempted,
        int organizationsSucceeded,
        int organizationsFailed,
        boolean lockAcquired,
        boolean completedFully
) {}
