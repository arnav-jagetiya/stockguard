# ADR-005: Separate Order and Reservation Lifecycles

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-02 (Updated 2026-11-03) |

> [!IMPORTANT]
> This is an architectural decision record. Nothing described here has been implemented or tested.

## Context

Orders represent **customer intent** — a customer requests to purchase specific items. Reservations represent **inventory commitment** — physical stock is held in warehouses to guarantee fulfillment.

Coupling orders and reservations tightly into a single entity or sharing state machines creates operational and architectural issues:

- **Expiration ambiguity** — if a temporary reservation expires due to checkout abandonment, the order entity itself should not necessarily be corrupted or deleted.
- **Cancellation ambiguity** — cancelling an order must release inventory; cancelling an unconfirmed hold should allow an order to be retried or expired independently.
- **State machine pollution** — conflating commercial order states (payment confirmation, fulfillment tracking) with physical inventory reservation states (holding, consuming, releasing) weakens domain boundaries.

## Decision

Orders and reservations maintain **strictly separate lifecycles and independent state machines**:

### 1. Independent Lifecycles

#### Order Lifecycle
- **`PENDING`**: Order placed, awaiting payment/confirmation. An active reservation is held.
- **`CONFIRMED`**: Order confirmed (e.g., payment cleared).
- **`FULFILLING`**: Order is actively being picked, packed, and processed for dispatch.
- **`FULFILLED`**: Order has been shipped/delivered. (Terminal state)
- **`CANCELLED`**: Order cancelled by customer or operator. (Terminal state)
- **`EXPIRED`**: Pending order expired due to unconfirmed reservation timeout. (Terminal state)

#### Reservation Lifecycle
- **`ACTIVE`**: The reservation is actively holding inventory across warehouse allocations.
- **`CONSUMED`**: The reservation has been fulfilled; inventory hold converted to permanent physical deduction. (Terminal state)
- **`RELEASED`**: The reservation was explicitly released due to order cancellation; inventory hold returned to available stock. (Terminal state)
- **`EXPIRED`**: The reservation timed out before order confirmation; inventory hold returned to available stock. (Terminal state)

> [!IMPORTANT]
> **Do not use `PENDING`, `CONFIRMED`, `FULFILLED`, or `CANCELLED` as reservation states.** Reservations possess strictly four states: `ACTIVE`, `CONSUMED`, `RELEASED`, and `EXPIRED`.

```
ORDER LIFECYCLE:
  [*] --> PENDING
  PENDING --> CONFIRMED: Payment Cleared / Order Confirmed
  PENDING --> EXPIRED: Hold TTL Elapsed
  PENDING --> CANCELLED: Explicit Cancellation
  CONFIRMED --> FULFILLING: Warehouse Processing Begun
  CONFIRMED --> CANCELLED: Cancelled Post-Confirmation
  FULFILLING --> FULFILLED: Shipment Dispatched
  FULFILLING --> CANCELLED: Aborted During Processing
  FULFILLED --> [*]
  CANCELLED --> [*]
  EXPIRED --> [*]

RESERVATION LIFECYCLE:
  [*] --> ACTIVE: Hold Created
  ACTIVE --> CONSUMED: Order Fulfilled
  ACTIVE --> RELEASED: Order Cancelled
  ACTIVE --> EXPIRED: Pending Hold Timed Out
  CONSUMED --> [*]
  RELEASED --> [*]
  EXPIRED --> [*]
```

### 2. Interaction and Invariants

- **Active State Semantics:** A reservation is `ACTIVE` while and only while it holds inventory.
- **Pending Order Expiration:** While an order is in `PENDING` status, its active reservation carries an `expires_at` timestamp. If the TTL elapses before the order is confirmed, the background worker releases the reservation (`ACTIVE → EXPIRED`) and marks the order as `EXPIRED`.
- **Confirmed Order Protection:** When an order transitions `PENDING → CONFIRMED`, its active reservation **does not automatically expire**. The `expires_at` timestamp is cleared (`NULL`) or bypassed by expiration workers.
- **Fulfillment Consumes Reservation:** Fulfilling an order (`CONFIRMED → FULFILLING → FULFILLED`) transitions the reservation `ACTIVE → CONSUMED`, deducting `on_hand` and `reserved` quantities simultaneously.
- **Cancellation Releases Reservation:** Cancelling an order transitions its active reservation `ACTIVE → RELEASED`, restoring held inventory back to available (`reserved` decreased, `on_hand` unchanged).
- **Expiration Releases Held Inventory:** When an unconfirmed reservation reaches expiration, it transitions `ACTIVE → EXPIRED`, restoring held inventory back to available (`reserved` decreased, `on_hand` unchanged).
- **Terminal State Invariant:** `CONSUMED`, `RELEASED`, and `EXPIRED` are strictly **terminal states**. A reservation in a terminal state **cannot return to `ACTIVE`**.
- **Single Active Reservation Per Order:** In the MVP, an order may have at most one active reservation.
- **No Partial Order Reservation:** Either all items across an entire order are successfully reserved, or the reservation fails and rolls back completely.
- **Historical Allocation Preservation & Hold Semantics:** `reservation_allocations` records remain permanently available after a reservation transitions to `CONSUMED`, `RELEASED`, or `EXPIRED`. Allocation rows are never deleted upon state transition. The parent `reservations.status` is the sole determinant of whether stock is actively held (`status = 'ACTIVE'`). Preserving allocation rows ensures full historical traceability of which warehouses allocated stock to the order.

## Consequences

### Benefits

- **Clear domain separation:** Orders track customer agreements and commercial intent; reservations track warehouse commitments and physical inventory holds.
- **Safe expiration boundaries:** Prevents confirmed orders from being prematurely cancelled by background timers while ensuring abandoned carts release stock promptly.
- **Clean state machines:** Explicit terminal states eliminate circular or ambiguous state transitions.

### Risks and Trade-offs

- **Coordination complexity:** The application service layer must orchestrate transitions across two independent entities in a single database transaction.

## Unresolved Questions

| # | Question |
|---|---|
| 1 | Reservation duration before confirmation — default TTL duration for `PENDING` order reservations (e.g., 15 min, 30 min, or configurable). |
| 2 | Re-reservation after expiration — whether an expired order can initiate a brand new reservation attempt or if a new order must be placed. |
