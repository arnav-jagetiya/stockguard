/**
 * Order module: manages commercial customer orders, order line items with immutable
 * historical snapshots (SKU, product name, unit price), and the independent 6-stage
 * order lifecycle ({@code PENDING}, {@code CONFIRMED}, {@code FULFILLING}, {@code FULFILLED},
 * {@code CANCELLED}, {@code EXPIRED}).
 * <p>
 * Managed database tables:
 * <ul>
 *   <li>{@code orders}</li>
 *   <li>{@code order_items}</li>
 * </ul>
 */
package com.stockguard.order;
