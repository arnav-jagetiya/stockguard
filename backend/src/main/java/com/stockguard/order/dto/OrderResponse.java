package com.stockguard.order.dto;

import com.stockguard.order.domain.Order;
import com.stockguard.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderResponse(
        UUID id,
        UUID organizationId,
        String orderNumber,
        String customerReference,
        String externalOrderReference,
        OrderStatus status,
        String currency,
        BigDecimal totalAmount,
        int itemCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static OrderResponse fromDomain(Order order, int itemCount) {
        return new OrderResponse(
                order.getId(),
                order.getOrganizationId(),
                order.getOrderNumber(),
                order.getCustomerReference(),
                order.getExternalOrderReference(),
                order.getStatus(),
                order.getCurrency(),
                order.getTotalAmount(),
                itemCount,
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
