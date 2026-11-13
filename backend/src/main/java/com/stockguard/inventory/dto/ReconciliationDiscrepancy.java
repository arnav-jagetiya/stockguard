package com.stockguard.inventory.dto;

import java.util.UUID;

public record ReconciliationDiscrepancy(
        UUID productId,
        UUID warehouseId,
        long balanceOnHand,
        long ledgerOnHandSum,
        long balanceReserved,
        long ledgerReservedSum,
        String discrepancyType
) {}
