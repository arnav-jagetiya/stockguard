package com.stockguard.reservation.dto;

/**
 * Summary telemetry payload returned by the reservation expiration background sweep.
 *
 * @param examinedCount Total number of candidates evaluated in this sweep (both successful and failed).
 * @param successCount  Number of candidate orders successfully transitioned to EXPIRED with inventory released.
 * @param skippedCount  Number of candidate orders skipped after lock acquisition due to revalidation failure.
 * @param failureCount  Number of candidates that encountered unexpected errors during transactional processing.
 * @param durationMs    Total wall-clock duration of the sweep execution in milliseconds.
 */
public record ExpirationBatchResult(
        int examinedCount,
        int successCount,
        int skippedCount,
        int failureCount,
        long durationMs
) {}
