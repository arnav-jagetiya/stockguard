package com.stockguard.reservation.repository;

import com.stockguard.reservation.domain.ReservationAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReservationAllocationRepository extends JpaRepository<ReservationAllocation, UUID> {

    List<ReservationAllocation> findAllByOrganizationIdAndReservationIdOrderByCreatedAtAsc(
            UUID organizationId,
            UUID reservationId
    );

    List<ReservationAllocation> findAllByOrganizationIdAndOrderId(UUID organizationId, UUID orderId);
}
