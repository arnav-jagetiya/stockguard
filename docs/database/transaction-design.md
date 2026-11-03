# Transaction Design

> **Status: PRELIMINARY DESIGN** — This document describes planned transaction flows, locking mechanisms, and concurrency patterns for STOCKGUARD. It is subject to review and approval. Nothing has been implemented.

## Accepted Architectural Decisions

| Decision | Reference | Summary |
|---|---|---|
| Transaction isolation level | ADR-003 | Use PostgreSQL `READ COMMITTED` with explicit row-level locking (`SELECT ... FOR UPDATE`) for critical inventory operations. |
| Canonical lock ordering | ADR-003 | Locks on `inventory_balances` are acquired strictly by `product_id ASC, warehouse_id ASC` in UUID order. |
| Append-only ledger | ADR-004 | Dual-delta recording (`on_hand_delta`, `reserved_delta`) committed atomically with balance updates. |
| Separate order and reservation lifecycles | ADR-005 | Order states (`PENDING`, `CONFIRMED`, `FULFILLING`, `FULFILLED`, `CANCELLED`, `EXPIRED`) and reservation states (`ACTIVE`, `CONSUMED`, `RELEASED`, `EXPIRED`) are independently tracked. |
| Deterministic allocation | ADR-006 | Business allocation priority is decoupled from canonical database lock order. |
| Idempotency guarantees | ADR-007 | Scoped idempotency records (`organization_id`, `principal_id`, `operation_scope`, `key`) with 7-day retention and hash validation. |
| Expiration concurrency | ADR-009 | Background expiration worker polls expired `ACTIVE` reservations for `PENDING` orders using `FOR UPDATE SKIP LOCKED`. |

---

## Concurrency & Coordination Patterns

### 1. Canonical Lock Ordering vs. Business Allocation Priority

To prevent deadlocks while supporting business warehouse preferences, the system strictly separates two concepts:

- **Business Allocation Order:** Evaluates warehouses based on:
  1. Prefer a single warehouse that can fulfill the complete order line.
  2. Otherwise split the line across eligible warehouses in configured warehouse-priority order (`priority` rank ascending).
  3. Use normalized warehouse `code` (ascending lexicographical order) as the deterministic tie-breaker.
  4. All-or-nothing: do not partially reserve an order.
- **Canonical Database Lock Order:** Once the allocation plan is computed, database row locks on `inventory_balances` are acquired strictly by:
  1. `product_id` ascending (UUID)
  2. `warehouse_id` ascending (UUID)

```sql
SELECT * FROM inventory_balances
WHERE (product_id, warehouse_id) IN (:planned_allocations)
ORDER BY product_id ASC, warehouse_id ASC
FOR UPDATE;
```

> [!IMPORTANT]
> Locks are **always** acquired in canonical database order (`product_id ASC, warehouse_id ASC`), never in business allocation order. This guarantees deadlock freedom across concurrent multi-item, multi-warehouse transactions.

### 2. Idempotency Integration Pattern

All mutating API transactions integrate idempotency as follows:

1. **Transaction Start & Concurrency Coordination:**
   - Attempt to insert an initial idempotency record with `status = 'IN_PROGRESS'`, `request_hash = :hash`, and `expires_at = NOW() + INTERVAL '7 days'`:
     ```sql
     INSERT INTO idempotency_records (
       id, organization_id, principal_id, operation_scope, idempotency_key, request_hash, status, created_at, expires_at
     ) VALUES (
       :idemp_id, :org_id, :principal_id, :scope, :key, :hash, 'IN_PROGRESS', NOW(), NOW() + INTERVAL '7 days'
     ) ON CONFLICT (organization_id, principal_id, operation_scope, idempotency_key) DO NOTHING;
     ```
   - **If the insert succeeds:** This request is the primary executor. Proceed immediately to business logic execution within this transaction.
   - **If the insert encounters a conflict (row exists or is being inserted concurrently):**
     - Acquire row lock on the existing record:
       ```sql
       SELECT * FROM idempotency_records
       WHERE organization_id = :org_id
         AND principal_id = :principal_id
         AND operation_scope = :scope
         AND idempotency_key = :key
       FOR UPDATE;
       ```
     - **Wait-and-Replay (Concurrent Duplicates):** If another concurrent transaction is executing (`status = 'IN_PROGRESS'`), the `FOR UPDATE` lock waits for that transaction to complete (commit or rollback).
     - **Upon Successful Commit of Prior Transaction:**
       - If `request_hash` matches stored hash and `status = 'COMPLETED'`: replay stored `response_status` and `response_body` immediately; roll back the current transaction without executing business logic.
       - If `request_hash` differs: return HTTP `409 Conflict` immediately; roll back the current transaction.
     - **Upon Rollback of Prior Transaction (Safe Retry):**
       - If the prior transaction rolled back due to error, PostgreSQL's rollback eliminates its uncommitted `IN_PROGRESS` row.
       - The current request unblocks, detects that no completed record exists, inserts the `IN_PROGRESS` record, and safely executes the business operation.
2. **Transaction End & Response Persistence:**
   - On successful execution, update the record to `status = 'COMPLETED'` and persist the response:
     ```sql
     UPDATE idempotency_records
     SET status = 'COMPLETED',
         response_status = :status_code,
         response_body = :body_json
     WHERE id = :idemp_id;
     ```
3. **Transactional Atomicity:** Idempotency record creation, business operations, and response persistence commit in the exact same database transaction.
4. **Client Disconnect Post-Commit Safety:** If a client connection drops after commit, a retry with the same key and payload replays the committed response without re-executing the business operation.
5. **Retention & Cleanup Requirement:** Proposed initial retention period is **7 days**. Expired records must be cleaned up by an asynchronous background job before a unique key can be safely reused.

---

## Critical Transaction Flows

### 1. Stock Receiving & Opening Balance

Increases on-hand inventory when goods arrive. If the product is being stocked at the warehouse for the first time, this is an `OPENING_BALANCE` transaction.

```sql
BEGIN;

  -- 1. Check idempotency
  SELECT * FROM idempotency_records
    WHERE organization_id = :org_id
      AND principal_id = :principal_id
      AND operation_scope = 'POST:/api/inventory/receive'
      AND idempotency_key = :key
    FOR UPDATE;
  -- If found with matching hash: return cached response and ROLLBACK.
  -- If found with differing hash: return 409 Conflict and ROLLBACK.

  -- 2. Lock inventory balance row in canonical order (product_id, warehouse_id)
  SELECT * FROM inventory_balances
    WHERE organization_id = :org_id
      AND product_id = :product_id
      AND warehouse_id = :warehouse_id
    FOR UPDATE;
  -- If row does not exist, INSERT with on_hand = 0, reserved = 0

  -- 3. Update inventory balance
  UPDATE inventory_balances
    SET on_hand = on_hand + :quantity,
        updated_at = NOW()
    WHERE organization_id = :org_id
      AND product_id = :product_id
      AND warehouse_id = :warehouse_id;

  -- 4. Create inventory transaction header
  INSERT INTO inventory_transactions (
    id, organization_id, transaction_type, reference_type, reference_id, notes, created_by, created_at
  ) VALUES (
    :tx_id, :org_id, :is_initial ? 'OPENING_BALANCE' : 'RECEIVE', 'RECEIPT', :receipt_id, :notes, :user_id, NOW()
  );

  -- 5. Append ledger entry with explicit dual deltas
  INSERT INTO inventory_ledger_entries (
    id, transaction_id, organization_id, warehouse_id, product_id, entry_type,
    on_hand_delta, reserved_delta, created_at
  ) VALUES (
    :entry_id, :tx_id, :org_id, :warehouse_id, :product_id, :is_initial ? 'OPENING_BALANCE' : 'RECEIVE',
    +:quantity, 0, NOW()
  );

  -- 6. Persist completed idempotency record (7-day retention)
  INSERT INTO idempotency_records (
    id, organization_id, principal_id, operation_scope, idempotency_key, request_hash, status, response_status, response_body, created_at, expires_at
  ) VALUES (
    :idemp_id, :org_id, :principal_id, 'POST:/api/inventory/receive', :key, :hash, 'COMPLETED', 201, :response_json, NOW(), NOW() + INTERVAL '7 days'
  );

COMMIT;
```

---

### 2. Order Creation & Reservation

Creates an order in `PENDING` status and an inventory reservation in `ACTIVE` status across allocated warehouses.

```sql
BEGIN;

  -- 1. Idempotency check & lock
  SELECT * FROM idempotency_records
    WHERE organization_id = :org_id
      AND principal_id = :principal_id
      AND operation_scope = 'POST:/api/orders'
      AND idempotency_key = :key
    FOR UPDATE;

  -- 2. Insert order in PENDING status
  INSERT INTO orders (id, organization_id, order_number, status, created_at, updated_at)
    VALUES (:order_id, :org_id, :order_number, 'PENDING', NOW(), NOW());

  -- 3. Insert order items with historical snapshots
  INSERT INTO order_items (id, organization_id, order_id, product_id, sku_snapshot, name_snapshot, quantity, unit_price, created_at)
    VALUES (:item_id, :org_id, :order_id, :product_id, :sku, :name, :qty, :price, NOW());

  -- 4. Compute allocation plan (business priority: complete line > priority rank > warehouse code tie-breaker)

  -- 5. Lock required inventory_balances strictly in canonical order:
  --    product_id ASC, warehouse_id ASC
  SELECT * FROM inventory_balances
    WHERE organization_id = :org_id
      AND (product_id, warehouse_id) IN (:allocation_pairs)
    ORDER BY product_id ASC, warehouse_id ASC
    FOR UPDATE;

  -- 6. Verify availability for each allocation:
  --    Assert: (on_hand - reserved) >= :allocated_qty
  --    If ANY allocation fails availability check:
  --      ROLLBACK;
  --      Return error (no partial order reservation in MVP).

  -- 7. Update reserved quantities on balances
  UPDATE inventory_balances
    SET reserved = reserved + :allocated_qty,
        updated_at = NOW()
    WHERE organization_id = :org_id
      AND product_id = :product_id
      AND warehouse_id = :warehouse_id;

  -- 8. Create reservation record in ACTIVE status with expiration TTL
  INSERT INTO reservations (id, organization_id, order_id, status, expires_at, created_at, updated_at)
    VALUES (:res_id, :org_id, :order_id, 'ACTIVE', NOW() + :configured_ttl, NOW(), NOW());

  -- 9. Create reservation allocations
  INSERT INTO reservation_allocations (id, organization_id, reservation_id, warehouse_id, product_id, quantity, created_at)
    VALUES (:alloc_id, :org_id, :res_id, :warehouse_id, :product_id, :allocated_qty, NOW());

  -- 10. Record inventory transaction and ledger entries (reserved_delta = +qty, on_hand_delta = 0)
  INSERT INTO inventory_transactions (...) VALUES (:tx_id, :org_id, 'RESERVE', 'ORDER', :order_id, ...);
  INSERT INTO inventory_ledger_entries (
    id, transaction_id, organization_id, warehouse_id, product_id, entry_type,
    on_hand_delta, reserved_delta, created_at
  ) VALUES (
    :entry_id, :tx_id, :org_id, :warehouse_id, :product_id, 'RESERVE',
    0, +:allocated_qty, NOW()
  );

  -- 11. Record idempotency (7-day TTL)
  INSERT INTO idempotency_records (...) VALUES (...);

COMMIT;
```

---

### 3. Order Confirmation

When payment or checkout confirms, the order transitions `PENDING → CONFIRMED`. The reservation remains `ACTIVE`, but does **not** automatically expire.

```sql
BEGIN;

  -- 1. Idempotency check & lock
  SELECT * FROM idempotency_records WHERE ... FOR UPDATE;

  -- 2. Lock order
  SELECT * FROM orders WHERE id = :order_id AND organization_id = :org_id FOR UPDATE;
  -- Assert order.status == 'PENDING'

  -- 3. Lock active reservation
  SELECT * FROM reservations WHERE order_id = :order_id AND status = 'ACTIVE' FOR UPDATE;
  -- Assert reservation exists and status == 'ACTIVE'

  -- 4. Transition order to CONFIRMED
  UPDATE orders SET status = 'CONFIRMED', updated_at = NOW() WHERE id = :order_id;

  -- 5. Clear or disable reservation expiration timestamp
  UPDATE reservations SET expires_at = NULL, updated_at = NOW() WHERE id = :reservation_id;

  -- 6. Record idempotency
  INSERT INTO idempotency_records (...) VALUES (...);

COMMIT;
```

---

### 4. Order Fulfillment

Converts held inventory into fulfilled goods. Transitions order `CONFIRMED → FULFILLING → FULFILLED` and consumes the reservation (`ACTIVE → CONSUMED`).

```sql
BEGIN;

  -- 1. Lock order and reservation
  SELECT * FROM orders WHERE id = :order_id FOR UPDATE;
  -- Assert order.status IN ('CONFIRMED', 'FULFILLING')

  SELECT * FROM reservations WHERE id = :reservation_id FOR UPDATE;
  -- Assert reservation.status == 'ACTIVE'

  -- 2. Lock inventory balance rows in canonical order: product_id ASC, warehouse_id ASC
  SELECT * FROM inventory_balances
    WHERE (product_id, warehouse_id) IN (
      SELECT product_id, warehouse_id FROM reservation_allocations WHERE reservation_id = :reservation_id
    )
    ORDER BY product_id ASC, warehouse_id ASC
    FOR UPDATE;

  -- 3. For each allocation:
  --    Deduct physical stock (on_hand) and logical hold (reserved)
  UPDATE inventory_balances
    SET on_hand = on_hand - :quantity,
        reserved = reserved - :quantity,
        updated_at = NOW()
    WHERE organization_id = :org_id
      AND product_id = :product_id
      AND warehouse_id = :warehouse_id;

  -- 4. Record transaction and ledger entries (both deltas negative)
  INSERT INTO inventory_transactions (...) VALUES (:tx_id, :org_id, 'FULFILL', 'ORDER', :order_id, ...);
  INSERT INTO inventory_ledger_entries (
    id, transaction_id, organization_id, warehouse_id, product_id, entry_type,
    on_hand_delta, reserved_delta, created_at
  ) VALUES (
    :entry_id, :tx_id, :org_id, :warehouse_id, :product_id, 'FULFILL',
    -:quantity, -:quantity, NOW()
  );

  -- 5. Transition reservation to terminal state CONSUMED
  UPDATE reservations SET status = 'CONSUMED', updated_at = NOW() WHERE id = :reservation_id;

  -- 6. Transition order to terminal state FULFILLED
  UPDATE orders SET status = 'FULFILLED', updated_at = NOW() WHERE id = :order_id;

  -- 7. Record idempotency
  INSERT INTO idempotency_records (...) VALUES (...);

COMMIT;
```

---

### 5. Order Cancellation

Cancelling an order releases its active reservation back to available inventory (`ACTIVE → RELEASED`).

```sql
BEGIN;

  -- 1. Lock order and reservation
  SELECT * FROM orders WHERE id = :order_id FOR UPDATE;
  SELECT * FROM reservations WHERE order_id = :order_id AND status = 'ACTIVE' FOR UPDATE;

  -- 2. Lock inventory balances in canonical order: product_id ASC, warehouse_id ASC
  SELECT * FROM inventory_balances
    WHERE (product_id, warehouse_id) IN (
      SELECT product_id, warehouse_id FROM reservation_allocations WHERE reservation_id = :reservation_id
    )
    ORDER BY product_id ASC, warehouse_id ASC
    FOR UPDATE;

  -- 3. Release reserved holds (on_hand untouched)
  UPDATE inventory_balances
    SET reserved = reserved - :quantity,
        updated_at = NOW()
    WHERE organization_id = :org_id
      AND product_id = :product_id
      AND warehouse_id = :warehouse_id;

  -- 4. Record transaction and ledger entries (reserved_delta = -qty, on_hand_delta = 0)
  INSERT INTO inventory_transactions (...) VALUES (:tx_id, :org_id, 'RELEASE', 'ORDER_CANCELLATION', :order_id, ...);
  INSERT INTO inventory_ledger_entries (
    id, transaction_id, organization_id, warehouse_id, product_id, entry_type,
    on_hand_delta, reserved_delta, created_at
  ) VALUES (
    :entry_id, :tx_id, :org_id, :warehouse_id, :product_id, 'RELEASE',
    0, -:quantity, NOW()
  );

  -- 5. Transition reservation to terminal state RELEASED
  UPDATE reservations SET status = 'RELEASED', updated_at = NOW() WHERE id = :reservation_id;

  -- 6. Transition order to terminal state CANCELLED
  UPDATE orders SET status = 'CANCELLED', updated_at = NOW() WHERE id = :order_id;

  -- 7. Record idempotency
  INSERT INTO idempotency_records (...) VALUES (...);

COMMIT;
```

---

### 6. Reservation Expiration (Background Worker)

A scheduled background worker releases expired active reservations for pending orders.

```sql
BEGIN;

  -- 1. Select a batch of expired ACTIVE reservations belonging to PENDING orders
  SELECT r.id AS reservation_id, r.order_id
    FROM reservations r
    JOIN orders o ON r.order_id = o.id
    WHERE r.status = 'ACTIVE'
      AND o.status = 'PENDING'
      AND r.expires_at < NOW()
    ORDER BY r.expires_at ASC
    LIMIT :batch_size
    FOR UPDATE OF r, o SKIP LOCKED;

  -- 2. For each locked reservation:
  --    Lock balance rows in canonical order (product_id ASC, warehouse_id ASC)
  SELECT * FROM inventory_balances
    WHERE (product_id, warehouse_id) IN (
      SELECT product_id, warehouse_id FROM reservation_allocations WHERE reservation_id = :res_id
    )
    ORDER BY product_id ASC, warehouse_id ASC
    FOR UPDATE;

  -- 3. Release reserved quantity (on_hand unchanged, reserved decreased)
  UPDATE inventory_balances
    SET reserved = reserved - :allocated_qty,
        updated_at = NOW()
    WHERE organization_id = :org_id
      AND product_id = :product_id
      AND warehouse_id = :warehouse_id;

  -- 4. Append ledger entries (reserved_delta = -qty, on_hand_delta = 0)
  INSERT INTO inventory_transactions (...) VALUES (:tx_id, :org_id, 'RELEASE', 'RESERVATION_EXPIRATION', :res_id, ...);
  INSERT INTO inventory_ledger_entries (
    id, transaction_id, organization_id, warehouse_id, product_id, entry_type,
    on_hand_delta, reserved_delta, created_at
  ) VALUES (
    :entry_id, :tx_id, :org_id, :warehouse_id, :product_id, 'RELEASE',
    0, -:allocated_qty, NOW()
  );

  -- 5. Transition reservation to terminal state EXPIRED
  UPDATE reservations SET status = 'EXPIRED', updated_at = NOW() WHERE id = :res_id;

  -- 6. Transition order to terminal state EXPIRED
  UPDATE orders SET status = 'EXPIRED', updated_at = NOW() WHERE id = :order_id;

COMMIT;
```

> [!NOTE]
> - Confirmed orders (`o.status = 'CONFIRMED'`) are excluded by the query and their active reservations **do not** expire.
> - Once in `EXPIRED`, `CONSUMED`, or `RELEASED`, a reservation is in a terminal state and cannot return to `ACTIVE`.
> - Allocation records (`reservation_allocations`) are never deleted upon reservation expiration, fulfillment, or cancellation; `reservations.status` controls whether inventory is actively held, while allocation rows permanently preserve historical allocation traceability.

---

### 7. Warehouse Transfer

Transfers stock between warehouses. Instant and atomic in MVP. Reserved stock cannot be transferred.

```sql
BEGIN;

  -- 1. Idempotency check & lock
  SELECT * FROM idempotency_records WHERE ... FOR UPDATE;

  -- 2. Lock balance rows in canonical order: product_id ASC, warehouse_id ASC
  --    For product_id, sort the two warehouses: w_first = LEAST(source, dest), w_second = GREATEST(source, dest)
  SELECT * FROM inventory_balances
    WHERE organization_id = :org_id
      AND product_id = :product_id
      AND warehouse_id = :w_first
    FOR UPDATE;

  SELECT * FROM inventory_balances
    WHERE organization_id = :org_id
      AND product_id = :product_id
      AND warehouse_id = :w_second
    FOR UPDATE;
  -- If destination balance row does not exist, insert initial row with on_hand = 0, reserved = 0.

  -- 3. Verify source available stock:
  --    Assert: (source.on_hand - source.reserved) >= :quantity
  --    Reserved inventory cannot be transferred.

  -- 4. Update balances
  UPDATE inventory_balances
    SET on_hand = on_hand - :quantity, updated_at = NOW()
    WHERE organization_id = :org_id AND warehouse_id = :source_warehouse_id AND product_id = :product_id;

  UPDATE inventory_balances
    SET on_hand = on_hand + :quantity, updated_at = NOW()
    WHERE organization_id = :org_id AND warehouse_id = :destination_warehouse_id AND product_id = :product_id;

  -- 5. Insert transfer records
  INSERT INTO transfers (id, organization_id, source_warehouse_id, destination_warehouse_id, status, created_by, created_at, updated_at)
    VALUES (:transfer_id, :org_id, :source_warehouse_id, :destination_warehouse_id, 'COMPLETED', :user_id, NOW(), NOW());

  INSERT INTO transfer_items (id, organization_id, transfer_id, product_id, quantity, created_at)
    VALUES (:item_id, :org_id, :transfer_id, :product_id, :quantity, NOW());

  -- 6. Insert inventory transaction and dual ledger entries
  INSERT INTO inventory_transactions (...) VALUES (:tx_id, :org_id, 'TRANSFER', 'TRANSFER', :transfer_id, ...);

  -- Source ledger entry
  INSERT INTO inventory_ledger_entries (
    id, transaction_id, organization_id, warehouse_id, product_id, entry_type,
    on_hand_delta, reserved_delta, created_at
  ) VALUES (
    :entry_src, :tx_id, :org_id, :source_warehouse_id, :product_id, 'TRANSFER_OUT',
    -:quantity, 0, NOW()
  );

  -- Destination ledger entry
  INSERT INTO inventory_ledger_entries (
    id, transaction_id, organization_id, warehouse_id, product_id, entry_type,
    on_hand_delta, reserved_delta, created_at
  ) VALUES (
    :entry_dst, :tx_id, :org_id, :destination_warehouse_id, :product_id, 'TRANSFER_IN',
    +:quantity, 0, NOW()
  );

  -- 7. Persist completed idempotency record (7-day retention)
  INSERT INTO idempotency_records (
    id, organization_id, principal_id, operation_scope, idempotency_key, request_hash, status, response_status, response_body, created_at, expires_at
  ) VALUES (
    :idemp_id, :org_id, :principal_id, 'POST:/api/transfers', :key, :hash, 'COMPLETED', 200, :response_json, NOW(), NOW() + INTERVAL '7 days'
  );

COMMIT;
```

---

## Unresolved Transaction Questions

| Question | Context | Status |
|---|---|---|
| Deadlock retry and backoff configuration | Exact retry limits, exponential backoff formula, and jitter algorithm for recovering from PostgreSQL `40P01` deadlock errors. | **Unresolved** |
| Lock timeout duration | Whether to set `lock_timeout` (e.g., 3s, 5s) per session/transaction to fail fast under extreme contention. | **Unresolved** |
| In-flight idempotency timeout & hang handling | Behavior if a transaction hangs or client drops connection while holding an `IN_PROGRESS` idempotency lock. | **Unresolved** |
| Background worker batch size & interval | Optimal batch size (e.g., 50 vs. 200) and polling interval (e.g., 15s vs. 30s) under high concurrency. | **Unresolved** |
| Negative stock adjustment behavior | Whether an adjustment reducing `on_hand` below `reserved` is rejected with an error or permitted with an audit alert. | **Unresolved** |
