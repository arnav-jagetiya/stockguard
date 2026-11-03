# ADR-003: Explicit Row-Level Pessimistic Locking for Inventory Operations

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-02 (Updated 2026-11-03) |

> [!IMPORTANT]
> This is an architectural decision record. Nothing described here has been implemented or tested.

## Context

Concurrent inventory operations — reservations, fulfillments, adjustments, transfers — targeting the same product and warehouse combination can cause:

- **Lost updates** — two concurrent operations read the same balance, both compute a new value, and the second write overwrites the first.
- **Negative balances** — two concurrent reservations each see sufficient stock and both succeed, resulting in over-reservation.

Alternative concurrency strategies considered:

- **Optimistic locking (`@Version`)** — detects conflicts at commit time and requires retries; under high contention on popular products, this causes retry storms and degraded throughput.
- **Application-level locks (e.g., `synchronized`, `ReentrantLock`)** — do not work across multiple application instances; not viable for horizontal scaling.
- **Serializable isolation level** — too broad; serializes entire transactions, not just the contended rows; significant performance impact.

## Decision

Use **explicit row-level pessimistic locking** (`SELECT ... FOR UPDATE`) for all critical inventory mutations:

- All operations that read-then-modify inventory balances acquire a row-level lock before reading.
- `inventory_balances` strictly stores physical `on_hand` and logical `reserved`. Available inventory is strictly derived (`available = on_hand - reserved`) and is never an independently mutable stored balance column.
- Use **`READ COMMITTED`** transaction isolation level with explicit locking (not `SERIALIZABLE`).
- **Canonical Lock Ordering:** To prevent deadlocks, transactions locking multiple inventory balance rows must acquire locks strictly in ascending UUID order:
  1. `product_id ASC`
  2. `warehouse_id ASC`
- **Decoupling from Allocation Policy:** Business allocation priority (warehouse priority rank, complete line preference, code tie-breaker) determines *which* warehouses contribute stock, but database row locks are acquired strictly in canonical lock order (`product_id ASC, warehouse_id ASC`).
- The reservation expiration worker uses **`FOR UPDATE SKIP LOCKED`** on expired `ACTIVE` reservations belonging to `PENDING` orders to avoid blocking other workers or user-facing operations (see [ADR-009](ADR-009-reservation-expiration.md)).

## Consequences

### Benefits

- **Prevents lost updates** — only one transaction can modify a given balance row at a time.
- **Prevents negative balances** — the lock ensures the balance check and update are atomic.
- **Serializes contending operations at the row level** — non-contending operations on different product/warehouse combinations proceed in parallel.
- **`FOR UPDATE SKIP LOCKED`** — enables concurrent expiration processing without blocking.
- **Deadlock elimination** — canonical lock ordering eliminates circular wait conditions across multi-item, multi-warehouse transactions.

### Risks and Trade-offs

- **Lock wait latency** — contending transactions block until the lock holder commits; high contention on a single product can increase latency.
- **Discipline required** — developers must adhere strictly to the canonical lock order across all database mutation paths.

## Unresolved Questions

| # | Question |
|---|---|
| 1 | Deadlock retry and backoff configuration — exact retry limits, jitter formula, and exponential backoff parameters for handling unexpected lock wait timeouts or PostgreSQL `40P01` errors. |
| 2 | Performance benchmarks under contention — what is the acceptable latency ceiling under peak concurrent reservation requests for a single hot product? |
