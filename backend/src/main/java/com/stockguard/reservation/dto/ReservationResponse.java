package com.stockguard.reservation.dto;

import com.stockguard.reservation.domain.Reservation;
import com.stockguard.reservation.domain.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID id,
        UUID organizationId,
        UUID orderId,
        ReservationStatus status,
        Instant expiresAt,
        List<ReservationAllocationResponse> allocations,
        Instant createdAt,
        Instant updatedAt
) {
    public static ReservationResponse fromDomain(Reservation reservation, List<ReservationAllocationResponse> allocations) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getOrganizationId(),
                reservation.getOrderId(),
                reservation.getStatus(),
                reservation.getExpiresAt(),
                allocations != null ? allocations : List.of(),
                reservation.getCreatedAt(),
                reservation.getUpdatedAt()
        );
    }
}
