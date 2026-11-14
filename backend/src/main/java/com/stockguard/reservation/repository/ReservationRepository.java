package com.stockguard.reservation.repository;

import com.stockguard.reservation.domain.Reservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    Optional<Reservation> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Reservation> findByOrganizationIdAndOrderId(UUID organizationId, UUID orderId);
}
