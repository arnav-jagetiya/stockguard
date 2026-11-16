package com.stockguard.demo.dto;

import java.util.UUID;

/**
 * Summary record capturing the outcome of demo environment initialization.
 */
public record DemoSeedResult(
        UUID organizationId,
        String organizationName,
        UUID adminUserId,
        String adminEmail,
        int userCount,
        int warehouseCount,
        int productCount,
        int openingBalanceCount,
        int orderCount,
        int reservationCount,
        int transferCount,
        int discrepancyCount,
        boolean alreadySeeded,
        String message
) {
    public static DemoSeedResult alreadySeeded(UUID organizationId, String organizationName) {
        return new DemoSeedResult(
                organizationId,
                organizationName,
                null,
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                true,
                "Demo environment for '" + organizationName + "' is already fully initialized."
        );
    }

    public static DemoSeedResult skipped(String reason) {
        return new DemoSeedResult(
                null,
                null,
                null,
                null,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                false,
                "Demo seeding skipped: " + reason
        );
    }
}
