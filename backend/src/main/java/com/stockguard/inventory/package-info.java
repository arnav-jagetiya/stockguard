/**
 * Inventory module: manages physical and logical stock balances, receiving batches,
 * manual stock adjustments, opening balances, dual-delta ledger entries, and mathematical
 * reconciliation.
 * <p>
 * Managed database tables:
 * <ul>
 *   <li>{@code inventory_balances}</li>
 *   <li>{@code inventory_transactions}</li>
 *   <li>{@code inventory_ledger_entries}</li>
 * </ul>
 * <p>
 * Persistence strategy: Spring JDBC ({@code NamedParameterJdbcTemplate}) is used for
 * critical high-concurrency balance locking (canonical lock order: {@code product_id ASC, warehouse_id ASC})
 * and batch ledger appends, while Spring Data JPA handles standard metadata queries.
 */
package com.stockguard.inventory;
