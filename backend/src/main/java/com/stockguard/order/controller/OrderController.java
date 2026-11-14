package com.stockguard.order.controller;

import com.stockguard.order.domain.OrderStatus;
import com.stockguard.order.dto.CreateOrderRequest;
import com.stockguard.order.dto.OrderDetailResponse;
import com.stockguard.order.dto.OrderResponse;
import com.stockguard.order.service.OrderService;
import com.stockguard.security.context.OrganizationContext;
import com.stockguard.security.context.OrganizationContextHolder;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('order:create')")
    public ResponseEntity<OrderResponse> createOrder(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        OrderResponse response = orderService.createOrder(
                context.organizationId(),
                context.userId(),
                request,
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('order:view')")
    public ResponseEntity<List<OrderResponse>> listOrders(
            @RequestParam(name = "status", required = false) OrderStatus status,
            @RequestParam(name = "limit", required = false, defaultValue = "50") int limit
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        List<OrderResponse> orders = orderService.listOrders(
                context.organizationId(),
                status,
                limit
        );
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('order:view')")
    public ResponseEntity<OrderDetailResponse> getOrder(
            @PathVariable UUID id
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        OrderDetailResponse order = orderService.getOrder(
                context.organizationId(),
                id
        );
        return ResponseEntity.ok(order);
    }

    @PostMapping("/{orderId}/confirm")
    @PreAuthorize("hasAuthority('order:confirm')")
    public ResponseEntity<OrderResponse> confirmOrder(
            @PathVariable UUID orderId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        OrderResponse response = orderService.confirmOrder(
                context.organizationId(),
                context.userId(),
                orderId,
                idempotencyKey
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{orderId}/cancel")
    @PreAuthorize("hasAuthority('order:cancel')")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable UUID orderId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        OrderResponse response = orderService.cancelOrder(
                context.organizationId(),
                context.userId(),
                orderId,
                idempotencyKey
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{orderId}/fulfill")
    @PreAuthorize("hasAuthority('order:fulfill')")
    public ResponseEntity<OrderResponse> fulfillOrder(
            @PathVariable UUID orderId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        OrderResponse response = orderService.fulfillOrder(
                context.organizationId(),
                context.userId(),
                orderId,
                idempotencyKey
        );
        return ResponseEntity.ok(response);
    }
}
