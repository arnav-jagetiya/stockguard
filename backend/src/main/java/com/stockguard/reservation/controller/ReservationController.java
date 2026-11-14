package com.stockguard.reservation.controller;

import com.stockguard.reservation.dto.CreateReservationRequest;
import com.stockguard.reservation.dto.ReservationResponse;
import com.stockguard.reservation.service.ReservationService;
import com.stockguard.security.context.OrganizationContext;
import com.stockguard.security.context.OrganizationContextHolder;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping("/api/reservations")
    @PreAuthorize("hasAuthority('reservation:create')")
    public ResponseEntity<ReservationResponse> createReservation(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateReservationRequest request
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        ReservationResponse response = reservationService.createReservation(
                context.organizationId(),
                context.userId(),
                request.orderId(),
                idempotencyKey
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/api/orders/{orderId}/reserve")
    @PreAuthorize("hasAuthority('reservation:create')")
    public ResponseEntity<ReservationResponse> reserveOrder(
            @PathVariable UUID orderId,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        ReservationResponse response = reservationService.createReservation(
                context.organizationId(),
                context.userId(),
                orderId,
                idempotencyKey
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/reservations/{id}")
    @PreAuthorize("hasAuthority('reservation:view')")
    public ResponseEntity<ReservationResponse> getReservation(
            @PathVariable UUID id
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        ReservationResponse response = reservationService.getReservation(
                context.organizationId(),
                id
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/api/orders/{orderId}/reservation")
    @PreAuthorize("hasAuthority('reservation:view')")
    public ResponseEntity<ReservationResponse> getReservationByOrderId(
            @PathVariable UUID orderId
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        ReservationResponse response = reservationService.getReservationByOrderId(
                context.organizationId(),
                orderId
        );
        return ResponseEntity.ok(response);
    }
}
