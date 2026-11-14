package com.stockguard.reservation.domain;

/**
 * Reservation lifecycle statuses matching PostgreSQL constraint {@code chk_reservations_status}.
 */
public enum ReservationStatus {
    ACTIVE,
    CONSUMED,
    RELEASED,
    EXPIRED
}
