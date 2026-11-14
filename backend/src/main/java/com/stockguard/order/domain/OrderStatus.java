package com.stockguard.order.domain;

/**
 * Permitted order lifecycle status matching check constraint {@code chk_orders_status}.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    FULFILLING,
    FULFILLED,
    CANCELLED,
    EXPIRED
}
