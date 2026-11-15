package com.stockguard.inventory.dto;

import java.util.List;

public record DiscrepancyPageResponse(
        List<DiscrepancyResponse> items,
        long totalCount,
        int limit,
        int offset
) {}
