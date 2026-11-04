/**
 * Reservation module: coordinates temporary inventory holds, deterministic multi-warehouse
 * allocations, reservation lifecycle transitions ({@code ACTIVE}, {@code CONSUMED},
 * {@code RELEASED}, {@code EXPIRED}), and background expiration scanning using
 * {@code FOR UPDATE SKIP LOCKED}.
 * <p>
 * Managed database tables:
 * <ul>
 *   <li>{@code reservations}</li>
 *   <li>{@code reservation_allocations}</li>
 * </ul>
 */
package com.stockguard.reservation;
