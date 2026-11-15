package com.stockguard.inventory.dto;

/**
 * Result summary of discrepancy synchronization for an organization sweep in Tx2.
 */
public record DiscrepancySyncResult(
        int newDiscrepancies,
        int repeatedObservations,
        int autoResolved,
        int parityVerifiedPreserved
) {}
