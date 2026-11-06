# ADR-009: Background Worker with FOR UPDATE SKIP LOCKED for Reservation Expiration

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-02 (Updated 2026-11-03) |

> [!IMPORTANT]
> This is an architectural decision record. Nothing described here has been implemented or tested.

## Context

Reservations held for pending checkout orders must expire after a configurable period to release held inventory back to the available pool. Without expiration, abandoned or timed-out orders permanently tie up available stock.

Key requirements:

- **Confirmed order protection:** A confirmed order's active reservation must **never** automatically expire.
- **Concurrent safety:** If multiple application instances run the expiration worker, they must not block each other or process the same reservation twice.
- **Independence:** Order and reservation lifecycles remain distinct; expiration transitions the reservation to `EXPIRED` and the order to `EXPIRED`.

PostgreSQL's `FOR UPDATE SKIP LOCKED` allows workers to lock batches of expired rows and skip rows currently locked by other transactions.

## Decision

Use a **Spring Boot background worker** (scheduled task) for reservation expiration:

- The worker runs on a configurable schedule (e.g., every 30 seconds).
- It queries for expired **`ACTIVE`** reservations belonging strictly to **`PENDING`** orders past their expiration time using the canonical lock hierarchy (`orders` before `reservations` before `inventory_balances`) with `FOR UPDATE SKIP LOCKED`:
  ```sql
  -- 1. Lock pending candidate orders first to avoid lock-order reversal deadlocks:
  SELECT o.id AS order_id, o.organization_id
  FROM orders o
  WHERE o.status = 'PENDING'
    AND EXISTS (
      SELECT 1 FROM reservations r
      WHERE r.order_id = o.id
        AND r.organization_id = o.organization_id
        AND r.status = 'ACTIVE'
        AND r.expires_at <= NOW()
    )
  ORDER BY o.id ASC
  LIMIT :batch_size
  FOR UPDATE OF o SKIP LOCKED;

  -- 2. Lock associated active reservation and re-verify post-lock status:
  SELECT id AS reservation_id, status, expires_at
  FROM reservations
  WHERE order_id = :order_id
    AND organization_id = :org_id
  FOR UPDATE;
  ```
- **Confirmed Order Exclusion:** Reservations for orders in `CONFIRMED`, `FULFILLING`, or `FULFILLED` status do **not** expire.
- For each locked reservation:
  - Inventory balance rows are locked in canonical order: `product_id ASC, warehouse_id ASC`.
  - The held inventory is released: `reserved` is decreased on `inventory_balances` (`on_hand` unchanged).
  - An `inventory_transactions` header and corresponding `inventory_ledger_entries` rows are inserted with `on_hand_delta = 0` and negative `reserved_delta`.
  - The reservation status is updated to terminal state **`EXPIRED`** (which cannot return to `ACTIVE`).
  - The order status is updated to terminal state **`EXPIRED`**.
- `FOR UPDATE SKIP LOCKED` ensures multiple worker instances can run concurrently without blocking or duplicate execution.

## Consequences

### Benefits

- **Safe expiration:** Only unconfirmed pending reservations expire; customer-confirmed orders are never prematurely released.
- **Concurrent-safe:** `FOR UPDATE SKIP LOCKED` prevents lock contention between background workers and customer transactions.
- **Stateless & resilient:** The worker relies entirely on database state; crashes and restarts resume cleanly on the next polling cycle.
- **No external dependencies:** Operates natively within PostgreSQL and Spring Boot without requiring Redis, Quartz, or RabbitMQ.

### Risks and Trade-offs

- **Polling delay:** Stock release occurs on the next worker cycle following the `expires_at` timestamp (bounded by the polling interval).
- **Periodic query load:** Background worker queries add periodic database load, mitigated by partial indexes on `(expires_at)` where `status = 'ACTIVE'`.

## Unresolved Questions

| # | Question |
|---|---|
| 1 | Polling interval and batch size — default schedule (e.g. 15s vs. 30s) and batch size (e.g. 50 vs. 200) under peak concurrency. |
| 2 | Worker observability — health checks and alerting thresholds if the expiration queue lags behind real-time timestamps. |
