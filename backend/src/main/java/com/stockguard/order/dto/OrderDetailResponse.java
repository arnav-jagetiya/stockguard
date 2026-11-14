package com.stockguard.order.dto;

import com.stockguard.order.domain.Order;
import com.stockguard.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OrderDetailResponse(
        UUID id,
        UUID organizationId,
        String orderNumber,
        String customerReference,
        String externalOrderReference,
        OrderStatus status,
        String currency,
        BigDecimal totalAmount,
        UUID actorUserId,
        List<OrderItemResponse> items,
        Instant createdAt,
        Instant updatedAt
) {
    public static OrderDetailResponse fromDomain(Order order, List<OrderItemResponse> items) {
        return new OrderDetailResponse(
                order.getId(),
                order.getOrganizationId(),
                order.getOrderNumber(),
                order.getCustomerReference(),
                order.getExternalOrderReference(),
                order.getStatus(),
                order.getCurrency(),
                order.getTotalAmount(),
                order.getActorUserId(),
                items,
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
