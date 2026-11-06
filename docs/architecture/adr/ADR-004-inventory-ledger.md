# ADR-004: Append-Only Inventory Ledger

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-02 (Updated 2026-11-03) |

> [!IMPORTANT]
> This is an architectural decision record. Nothing described here has been implemented or tested.

## Context

Inventory balance changes must be auditable and traceable. A mutable balance table alone provides no history of how the current balance was reached. Financial, regulatory, and compliance requirements demand a complete record of all inventory movements — who changed what, when, why, and by how much.

Without a ledger:

- There is no way to answer "why does this product have 47 units?" without examining application logs.
- Reconciliation is impossible — there is no independent mathematical check to verify that the current balance is correct.
- Debugging inventory discrepancies requires log archaeology.

## Decision

Maintain an **append-only `inventory_ledger_entries` table** (grouped logically by `inventory_transactions`) alongside `inventory_balances`:

### 1. Dual-Delta Model
Every ledger entry explicitly captures two independent, signed deltas:
- **`on_hand_delta`**: Signed physical stock modification (+ for stock receipt/opening balance, - for shipment/fulfillment, 0 for pure reservations).
- **`reserved_delta`**: Signed logical commitment modification (+ for reservation creation, - for fulfillment/release/expiration, 0 for pure physical adjustments).

Current authoritative balance positions (`on_hand` and `reserved`) are maintained directly on `inventory_balances`, while `inventory_ledger_entries` records pure signed deltas grouped by transaction.

### 2. Opening Balance as a Ledger Transaction
When a product is first stocked at a warehouse, its initial quantity is established via an explicit `OPENING_BALANCE` transaction and ledger entry. No balance record may ever exist without an originating ledger entry.

### 3. Atomic Updates
Every update to `inventory_balances` and its corresponding insert into `inventory_ledger_entries` (and parent `inventory_transactions`) **must commit within the exact same database transaction**. There is never a balance change without a ledger record, nor an orphaned ledger entry.

### 4. Append-Only History
Ledger entries are strictly **immutable**. No `UPDATE` or `DELETE` operations are permitted. Corrections are executed by recording new compensating transactions and entries.

### 5. Mathematical Reconciliation
Reconciliation between balances and ledger entries is calculated by summing historical deltas:
$$\text{inventory\_balances.on\_hand} = \sum \text{inventory\_ledger\_entries.on\_hand\_delta}$$
$$\text{inventory\_balances.reserved} = \sum \text{inventory\_ledger\_entries.reserved\_delta}$$
$$\text{available} = \text{inventory\_balances.on\_hand} - \text{inventory\_balances.reserved}$$

Any discrepancy between balance snapshots and the sum of ledger deltas indicates a data integrity fault and triggers an immediate reconciliation alert.

## Consequences

### Benefits

- **Full financial-grade audit trail:** Every stock mutation is permanently documented with business context and actor attribution.
- **Independent mathematical verification:** Reconciliation directly verifies the integrity of the mutable balance table against the immutable ledger.
- **Disaster recovery & debugging:** In the event of data corruption, accurate balance states can be fully reconstructed from the ledger history.
- **Compliance:** Satisfies strict regulatory requirements for inventory accounting.

### Risks and Trade-offs

- **Storage growth:** The ledger grows continuously with every operation; high-velocity operations require partitioning and retention planning.
- **Aggregation cost:** Running a full ledger sum over millions of rows for reconciliation is expensive and should be executed on an asynchronous, scheduled basis or bounded by checkpoints.

## Unresolved Questions

| # | Question |
|---|---|
| 1 | Ledger archival strategy — when and how should historical ledger entries be archived or moved to cold storage without breaking reconciliation? |
| 2 | Reconciliation job schedule and scope — should reconciliation run on a nightly cron, on-demand per product/warehouse, or via continuous checkpointing? |
| 3 | Ledger table partitioning — should `inventory_ledger_entries` be range-partitioned by `created_at` (e.g., monthly) from day one? |
