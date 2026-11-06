# ADR-006: Multi-Warehouse Support and Deterministic Allocation

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-02 (Updated 2026-11-03) |

> [!IMPORTANT]
> This is an architectural decision record. Nothing described here has been implemented or tested.

## Context

Retailers and distributors typically operate multiple warehouses or fulfillment centers. Orders may need to be fulfilled from inventory spread across multiple locations. The allocation logic — deciding which warehouse(s) supply which items — must be:

- **Deterministic** — given the same inputs (order items, warehouse inventories, configured priorities), the allocation produces identical warehouse assignments every time.
- **Repeatable** — re-running allocation for the same order yields the same result.
- **Transparent** — the system records which warehouse was assigned to fulfill each item.

Non-deterministic allocation (e.g., random selection, time-dependent logic) makes debugging, reconciliation, and retry logic unreliable.

Furthermore, business allocation logic must be strictly decoupled from database concurrency control to prevent deadlocks.

## Decision

### 1. Multi-Warehouse Support
- **Support multiple warehouses per organization** — each organization operates one or more warehouses, each with independent inventory balances.
- **Reservations include reservation allocations** — a reservation records per-warehouse, per-product reserved quantities (`reservation_allocations`).

### 2. Agreed Initial Allocation Policy
Allocation follows a deterministic multi-step strategy for each order line item:

1. **Complete Line Preference:** Prefer a single warehouse that has sufficient `available` stock (`on_hand - reserved >= requested_quantity`) to fulfill the complete line item quantity. If multiple warehouses can fulfill the line completely, select the one with highest priority rank (lowest `priority` integer).
2. **Split Across Eligible Warehouses:** If no single warehouse can satisfy the line completely, allocate available stock across eligible warehouses in configured warehouse-priority order until the required line quantity is satisfied.
3. **Deterministic Tie-Breaker:** When warehouses have identical priority values, the normalized warehouse `code` in ascending lexicographical order acts as the deterministic tie-breaker.
4. **No Partial Order Reservation:** Either all items across the entire order are fully allocated and reserved, or the reservation fails completely and rolls back (all-or-nothing).

### 3. Separation of Allocation Logic and Canonical Database Lock Ordering
- **Business Allocation Order:** Governed by `priority ASC, code ASC` to determine which warehouses contribute stock.
- **Canonical Database Lock Acquisition Order:** Governed strictly by canonical database ordering to prevent deadlocks during concurrent multi-item, multi-warehouse transactions:
  1. `product_id ASC` (UUID)
  2. `warehouse_id ASC` (UUID)

> [!IMPORTANT]
> Database locks on `inventory_balances` are **always** acquired in canonical order (`product_id ASC, warehouse_id ASC`), never in business allocation order.

## Consequences

### Benefits

- **Deterministic and reproducible:** Order allocation results are completely predictable and testable.
- **Reduced split shipments:** Preferring complete line fulfillment minimizes fragmentation across warehouses.
- **Deadlock risk reduction:** Strict decoupling of business allocation order from database lock acquisition eliminates circular wait conditions among inventory balance rows across concurrent multi-item transactions.
- **Auditability:** Allocation decisions are permanently recorded in `reservation_allocations` and `inventory_ledger_entries`.

### Risks and Trade-offs

- **Cross-warehouse coordination:** Splitting orders across warehouses increases fulfillment and shipping complexity.
- **Lock contention:** Multi-warehouse transactions hold locks across multiple balance rows until commit.

## Unresolved Questions

| # | Question |
|---|---|
| 1 | Warehouse capacity constraints — should allocation incorporate throughput limits or operational cutoff times, rather than purely static priority? |
| 2 | Dynamic routing / geo-proximity — should physical destination shipping address influence priority dynamically in future iterations? |
