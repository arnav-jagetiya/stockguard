package com.stockguard.order.dto;

import com.stockguard.order.domain.OrderItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderItemResponse(
        UUID id,
        int lineNumber,
        UUID productId,
        String skuSnapshot,
        String nameSnapshot,
        long quantity,
        BigDecimal unitPrice,
        Instant createdAt
) {
    public static OrderItemResponse fromDomain(OrderItem item) {
        return new OrderItemResponse(
                item.getId(),
                item.getLineNumber(),
                item.getProductId(),
                item.getSkuSnapshot(),
                item.getNameSnapshot(),
                item.getQuantity(),
                item.getUnitPrice(),
                item.getCreatedAt()
        );
    }
}
