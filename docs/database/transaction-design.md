# Transaction Design

> **Status: READY FOR FINAL REVIEW — PENDING PROJECT OWNER SIGN-OFF** — This document describes transaction flows, locking mechanisms, and concurrency patterns for STOCKGUARD Milestone 2, aligned with the authoritative [DATABASE_SCHEMA_SPECIFICATION.md](DATABASE_SCHEMA_SPECIFICATION.md). No tables, DDL scripts, or Flyway migrations have been created yet.

## Accepted Architectural Decisions

| Decision | Reference | Summary |
|---|---|---|
| Transaction isolation level | ADR-003 | Use PostgreSQL `READ COMMITTED` with explicit row-level locking (`SELECT ... FOR UPDATE`) for critical inventory operations. |
| Canonical lock ordering | ADR-003 | Locks on `inventory_balances` are acquired strictly by `product_id ASC, warehouse_id ASC` in UUID order. |
| Append-only ledger | ADR-004 | Dual-delta recording (`on_hand_delta`, `reserved_delta`) committed atomically with balance updates. |
| Separate order and reservation lifecycles | ADR-005 | Order states (`PENDING`, `CONFIRMED`, `FULFILLING`, `FULFILLED`, `CANCELLED`, `EXPIRED`) and reservation states (`ACTIVE`, `CONSUMED`, `RELEASED`, `EXPIRED`) are independently tracked. |
| Deterministic allocation | ADR-006 | Business allocation priority is decoupled from canonical database lock order. |
| Idempotency guarantees | ADR-007 | Scoped idempotency records (`organization_id`, `principal_type`, `principal_id`, `operation_scope`, `idempotency_key`) with 7-day retention and hash validation. |
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
> Locks are **always** acquired in canonical database order (`product_id ASC, warehouse_id ASC`), never in business allocation order. Sorting balance rows eliminates circular-wait deadlocks among balance rows if all transactions adhere to this canonical order. However, transactions involving multiple entities (such as orders and reservations) or external contention can still encounter deadlocks; application-level deadlock retry handling remains an explicitly unresolved decision.

### 2. Idempotency Integration Pattern

All mutating API transactions integrate idempotency as follows:

1. **Transaction Start & Concurrency Coordination:**
   - Attempt to insert an initial idempotency record with `request_hash = :hash`, `response_status = NULL`, `response_body = NULL`, and `expires_at = NOW() + INTERVAL '7 days'`:
     ```sql
     INSERT INTO idempotency_records (
       id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, response_status, response_body, created_at, expires_at
     ) VALUES (
       :idemp_id, :org_id, 'USER', :principal_id, :scope, :key, :hash, NULL, NULL, NOW(), NOW() + INTERVAL '7 days'
     ) ON CONFLICT (organization_id, principal_type, principal_id, operation_scope, idempotency_key) DO NOTHING
     RETURNING id;
     ```
   - **PostgreSQL READ COMMITTED Concurrency Dynamics & Ownership Detection:**
     - The initial `INSERT ... ON CONFLICT DO NOTHING RETURNING id` handles concurrent unique-key conflict resolution. Because the statement uses `RETURNING id`, it produces a result set and must be consumed through a JDBC result-set/query API (such as `PreparedStatement.executeQuery()` or Spring JDBC query APIs like `NamedParameterJdbcTemplate.query()`), rather than `executeUpdate()`.
     - Under PostgreSQL `READ COMMITTED`, if a competing transaction is concurrently executing with the same key, this insert blocks on the unique index conflict until the competing transaction terminates:
       - **One returned row:** This request inserted the idempotency record and owns the key. The returned ID identifies its record. If a competing transaction rolled back, the competing speculative row was eliminated, allowing the waiting insert to proceed and insert its own row. The current request becomes the primary executor and proceeds immediately with business logic execution. **No second INSERT is required.**
       - **No returned rows:** The insert did not occur because the unique-key conflict was handled by `DO NOTHING` after the competing transaction committed (or an existing committed record was already present). Follow the duplicate/replay path and read the existing committed record:
         - Read the existing committed record with a row lock:
           ```sql
           SELECT * FROM idempotency_records
           WHERE organization_id = :org_id
             AND principal_type = 'USER'
             AND principal_id = :principal_id
             AND operation_scope = :scope
             AND idempotency_key = :key
           FOR UPDATE;
           ```
         - **Replay / Conflict Evaluation:**
           - If `request_hash` matches stored hash and a committed response is present (`response_status IS NOT NULL`): replay stored `response_status` and `response_body` immediately; roll back the current transaction without executing business logic.
           - If `request_hash` differs: return HTTP `409 Conflict` immediately; roll back the current transaction.
2. **Transaction End & Response Persistence:**
   - On successful execution, update the initially inserted idempotency row with the committed response fields before committing:
     ```sql
     UPDATE idempotency_records
     SET response_status = :status_code,
         response_body = :body_json
     WHERE id = :idemp_id
       AND organization_id = :org_id;
     ```
   - **No Completed-Only Insert Alternative:** Deferring the idempotency insert until transaction completion does not establish early key ownership, does not prevent concurrent duplicate execution, and cannot provide wait-and-replay serialization. Inserting a completed record only at transaction end is therefore strictly prohibited for all mutating operations.
3. **Transactional Atomicity:** Initial idempotency record creation, business operations, and response persistence commit in the exact same database transaction.
4. **Client Disconnect Post-Commit Safety:** If a client connection drops after commit, a retry with the same key and payload replays the committed response without re-executing the business operation.
5. **Retention & Cleanup Requirement:** Proposed initial retention period is **7 days**. Expired records must be cleaned up by an asynchronous background job before a unique key can be safely reused.

---

## Critical Transaction Flows

### 1. Stock Receiving & Opening Balance

Increases on-hand inventory when goods arrive. If the product is being stocked at the warehouse for the first time, this is an `OPENING_BALANCE` transaction.

```sql
BEGIN;

  -- 1. Establish idempotency ownership / Wait-and-Replay
  INSERT INTO idempotency_records (
    id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, response_status, response_body, created_at, expires_at
  ) VALUES (
    :idemp_id, :org_id, 'USER', :principal_id, 'POST:/api/inventory/receive', :key, :hash, NULL, NULL, NOW(), NOW() + INTERVAL '7 days'
  ) ON CONFLICT (organization_id, principal_type, principal_id, operation_scope, idempotency_key) DO NOTHING
  RETURNING id;

  -- Ownership Detection & PostgreSQL READ COMMITTED Concurrency:
  -- (Consume result via query API, e.g., PreparedStatement.executeQuery() or Spring query API):
  -- - One returned row: this request inserted the idempotency record and owns the key. The returned ID identifies its record.
  --     Proceed to step 2 as primary executor.
  --     (If a competing transaction rolled back, this waiting insert unblocked and inserted its own row — no second INSERT required.)
  -- - No returned rows: the insert did not occur because the unique-key conflict was handled by DO NOTHING.
  --     Follow the duplicate/replay path and read the existing committed record with row lock:
  --       SELECT * FROM idempotency_records
  --         WHERE organization_id = :org_id AND principal_type = 'USER' AND principal_id = :principal_id
  --           AND operation_scope = 'POST:/api/inventory/receive' AND idempotency_key = :key
  --         FOR UPDATE;
  --       * If request_hash matches and response_status IS NOT NULL: replay cached response and ROLLBACK.
  --       * If request_hash differs: return HTTP 409 Conflict and ROLLBACK.

  -- 2. Ensure balance row exists, then acquire lock in canonical order
  INSERT INTO inventory_balances (
    id, organization_id, product_id, warehouse_id, on_hand, reserved, created_at, updated_at
  ) VALUES (
    :balance_id, :org_id, :product_id, :warehouse_id, 0, 0, NOW(), NOW()
  ) ON CONFLICT (organization_id, product_id, warehouse_id) DO NOTHING;

  SELECT * FROM inventory_balances
    WHERE organization_id = :org_id
      AND product_id = :product_id
      AND warehouse_id = :warehouse_id
    FOR UPDATE;

  -- 3. Update inventory balance
  UPDATE inventory_balances
    SET on_hand = on_hand + :quantity,
        updated_at = NOW()
    WHERE organization_id = :org_id
      AND product_id = :product_id
      AND warehouse_id = :warehouse_id;

  -- 4. Create inventory transaction header (standalone receipts have no external reference entity in MVP)
  INSERT INTO inventory_transactions (
    id, organization_id, transaction_type, reference_type, reference_id, notes, actor_user_id, created_at
  ) VALUES (
    :tx_id, :org_id, :is_initial ? 'OPENING_BALANCE' : 'RECEIPT', NULL, NULL, :notes, :user_id, NOW()
  );

  -- 5. Append ledger entry with explicit dual deltas
  INSERT INTO inventory_ledger_entries (
    id, transaction_id, organization_id, warehouse_id, product_id, entry_number,
    on_hand_delta, reserved_delta, created_at
  ) VALUES (
    :entry_id, :tx_id, :org_id, :warehouse_id, :product_id, 1,
    +:quantity, 0, NOW()
  );

  -- 6. Populate response and commit idempotency record
  UPDATE idempotency_records
    SET response_status = 201,
        response_body = :response_json
    WHERE id = :idemp_id
      AND organization_id = :org_id;

COMMIT;
```

---

### 2. Order Creation & Reservation

Creates an order in `PENDING` status and an inventory reservation in `ACTIVE` status across allocated warehouses.

```sql
BEGIN;

  -- 1. Establish idempotency ownership / Wait-and-Replay
  INSERT INTO idempotency_records (
    id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, response_status, response_body, created_at, expires_at
  ) VALUES (
    :idemp_id, :org_id, 'USER', :principal_id, 'POST:/api/orders', :key, :hash, NULL, NULL, NOW(), NOW() + INTERVAL '7 days'
  ) ON CONFLICT (organization_id, principal_type, principal_id, operation_scope, idempotency_key) DO NOTHING
  RETURNING id;

  -- Ownership Detection & PostgreSQL READ COMMITTED Concurrency:
  -- (Consume result via query API, e.g., PreparedStatement.executeQuery() or Spring query API):
  -- - One returned row: this request inserted the idempotency record and owns the key. The returned ID identifies its record.
  --     Proceed to step 2 as primary executor.
  --     (If a competing transaction rolled back, this waiting insert unblocked and inserted its own row — no second INSERT required.)
  -- - No returned rows: the insert did not occur because the unique-key conflict was handled by DO NOTHING.
  --     Follow the duplicate/replay path and read the existing committed record with row lock:
  --       SELECT * FROM idempotency_records
  --         WHERE organization_id = :org_id AND principal_type = 'USER' AND principal_id = :principal_id
  --           AND operation_scope = 'POST:/api/orders' AND idempotency_key = :key
  --         FOR UPDATE;
  --       * If request_hash matches and response_status IS NOT NULL: replay cached response and ROLLBACK.
  --       * If request_hash differs: return HTTP 409 Conflict and ROLLBACK.

  -- 2. Insert order in PENDING status
  INSERT INTO orders (id, organization_id, order_number, status, currency, actor_user_id, created_at, updated_at)
    VALUES (:order_id, :org_id, :order_number, 'PENDING', 'USD', :user_id, NOW(), NOW());

  -- 3. Insert order items with historical snapshots
  INSERT INTO order_items (id, organization_id, order_id, line_number, product_id, sku_snapshot, name_snapshot, quantity, unit_price, created_at)
    VALUES (:item_id, :org_id, :order_id, 1, :product_id, :sku, :name, :qty, :price, NOW());

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

  -- 9. Create reservation allocations (composite FKs enforce org, order, and product alignment)
  INSERT INTO reservation_allocations (id, organization_id, reservation_id, order_id, order_item_id, warehouse_id, product_id, quantity, created_at)
    VALUES (:alloc_id, :org_id, :res_id, :order_id, :item_id, :warehouse_id, :product_id, :allocated_qty, NOW());

  -- 10. Record inventory transaction and ledger entries (reserved_delta = +qty, on_hand_delta = 0)
  INSERT INTO inventory_transactions (
    id, organization_id, transaction_type, reference_type, reference_id, notes, actor_user_id, created_at
  ) VALUES (
    :tx_id, :org_id, 'RESERVATION', 'ORDER', :order_id, :notes, :user_id, NOW()
  );

  INSERT INTO inventory_ledger_entries (
    id, transaction_id, organization_id, warehouse_id, product_id, entry_number,
    on_hand_delta, reserved_delta, created_at
  ) VALUES (
    :entry_id, :tx_id, :org_id, :warehouse_id, :product_id, 1,
    0, +:allocated_qty, NOW()
  );

  -- 11. Populate response and commit idempotency record
  UPDATE idempotency_records
    SET response_status = 201,
        response_body = :response_json
    WHERE id = :idemp_id
      AND organization_id = :org_id;

COMMIT;
```

---

### 3. Order Confirmation

When payment or checkout confirms, the order transitions `PENDING → CONFIRMED`. The reservation remains `ACTIVE`, but does **not** automatically expire.

```sql
BEGIN;

  -- 1. Establish idempotency ownership / Wait-and-Replay
  INSERT INTO idempotency_records (
    id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, response_status, response_body, created_at, expires_at
  ) VALUES (
    :idemp_id, :org_id, 'USER', :principal_id, 'POST:/api/orders/{id}/confirm', :key, :hash, NULL, NULL, NOW(), NOW() + INTERVAL '7 days'
  ) ON CONFLICT (organization_id, principal_type, principal_id, operation_scope, idempotency_key) DO NOTHING
  RETURNING id;

  -- Ownership Detection & PostgreSQL READ COMMITTED Concurrency:
  -- (Consume result via query API, e.g., PreparedStatement.executeQuery() or Spring query API):
  -- - One returned row: this request inserted the idempotency record and owns the key. The returned ID identifies its record.
  --     Proceed to step 2 as primary executor.
  --     (If a competing transaction rolled back, this waiting insert unblocked and inserted its own row — no second INSERT required.)
  -- - No returned rows: the insert did not occur because the unique-key conflict was handled by DO NOTHING.
  --     Follow the duplicate/replay path and read the existing committed record with row lock:
  --       SELECT * FROM idempotency_records
  --         WHERE organization_id = :org_id AND principal_type = 'USER' AND principal_id = :principal_id
  --           AND operation_scope = 'POST:/api/orders/{id}/confirm' AND idempotency_key = :key
  --         FOR UPDATE;
  --       * If request_hash matches and response_status IS NOT NULL: replay cached response and ROLLBACK.
  --       * If request_hash differs: return HTTP 409 Conflict and ROLLBACK.

  -- 2. Lock order
  SELECT * FROM orders WHERE id = :order_id AND organization_id = :org_id FOR UPDATE;
  -- Assert order.status == 'PENDING'

  -- 3. Lock active reservation
  SELECT * FROM reservations WHERE order_id = :order_id AND organization_id = :org_id AND status = 'ACTIVE' FOR UPDATE;
  -- Assert reservation exists and status == 'ACTIVE'

  -- 4. Transition order to CONFIRMED
  UPDATE orders SET status = 'CONFIRMED', updated_at = NOW() WHERE id = :order_id AND organization_id = :org_id;

  -- 5. Clear or disable reservation expiration timestamp
  UPDATE reservations SET expires_at = NULL, updated_at = NOW() WHERE id = :reservation_id AND organization_id = :org_id;

  -- 6. Populate response and commit idempotency record
  UPDATE idempotency_records
    SET response_status = 200,
        response_body = :response_json
    WHERE id = :idemp_id
      AND organization_id = :org_id;

COMMIT;
```

---

### 4. Order Fulfillment

Converts held inventory into fulfilled goods. Transitions order `CONFIRMED → FULFILLING → FULFILLED` and consumes the reservation (`ACTIVE → CONSUMED`).

```sql
BEGIN;

  -- 1. Establish idempotency ownership / Wait-and-Replay
  INSERT INTO idempotency_records (
    id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, response_status, response_body, created_at, expires_at
  ) VALUES (
    :idemp_id, :org_id, 'USER', :principal_id, 'POST:/api/orders/{id}/fulfill', :key, :hash, NULL, NULL, NOW(), NOW() + INTERVAL '7 days'
  ) ON CONFLICT (organization_id, principal_type, principal_id, operation_scope, idempotency_key) DO NOTHING
  RETURNING id;

  -- Ownership Detection & PostgreSQL READ COMMITTED Concurrency:
  -- (Consume result via query API, e.g., PreparedStatement.executeQuery() or Spring query API):
  -- - One returned row: this request inserted the idempotency record and owns the key. The returned ID identifies its record.
  --     Proceed to step 2 as primary executor.
  --     (If a competing transaction rolled back, this waiting insert unblocked and inserted its own row — no second INSERT required.)
  -- - No returned rows: the insert did not occur because the unique-key conflict was handled by DO NOTHING.
  --     Follow the duplicate/replay path and read the existing committed record with row lock:
  --       SELECT * FROM idempotency_records
  --         WHERE organization_id = :org_id AND principal_type = 'USER' AND principal_id = :principal_id
  --           AND operation_scope = 'POST:/api/orders/{id}/fulfill' AND idempotency_key = :key
  --         FOR UPDATE;
  --       * If request_hash matches and response_status IS NOT NULL: replay cached response and ROLLBACK.
  --       * If request_hash differs: return HTTP 409 Conflict and ROLLBACK.

  -- 2. Lock order and reservation
  SELECT * FROM orders WHERE id = :order_id AND organization_id = :org_id FOR UPDATE;
  -- Assert order.status IN ('CONFIRMED', 'FULFILLING')

  SELECT * FROM reservations WHERE id = :reservation_id AND organization_id = :org_id FOR UPDATE;
  -- Assert reservation.status == 'ACTIVE'

  -- 3. Lock inventory balance rows in canonical order: product_id ASC, warehouse_id ASC
  SELECT * FROM inventory_balances
    WHERE organization_id = :org_id
      AND (product_id, warehouse_id) IN (
        SELECT product_id, warehouse_id FROM reservation_allocations
        WHERE reservation_id = :reservation_id AND organization_id = :org_id
      )
    ORDER BY product_id ASC, warehouse_id ASC
    FOR UPDATE;

  -- 4. For each allocation:
  --    Deduct physical stock (on_hand) and logical hold (reserved)
  UPDATE inventory_balances
    SET on_hand = on_hand - :quantity,
        reserved = reserved - :quantity,
        updated_at = NOW()
    WHERE organization_id = :org_id
      AND product_id = :product_id
      AND warehouse_id = :warehouse_id;

  -- 5. Record transaction and ledger entries (both deltas negative)
  INSERT INTO inventory_transactions (
    id, organization_id, transaction_type, reference_type, reference_id, notes, actor_user_id, created_at
  ) VALUES (
    :tx_id, :org_id, 'FULFILLMENT', 'ORDER', :order_id, :notes, :user_id, NOW()
  );

  INSERT INTO inventory_ledger_entries (
    id, transaction_id, organization_id, warehouse_id, product_id, entry_number,
    on_hand_delta, reserved_delta, created_at
  ) VALUES (
    :entry_id, :tx_id, :org_id, :warehouse_id, :product_id, 1,
    -:quantity, -:quantity, NOW()
  );

  -- 6. Transition reservation to terminal state CONSUMED
  UPDATE reservations SET status = 'CONSUMED', updated_at = NOW() WHERE id = :reservation_id AND organization_id = :org_id;

  -- 7. Transition order to terminal state FULFILLED
  UPDATE orders SET status = 'FULFILLED', updated_at = NOW() WHERE id = :order_id AND organization_id = :org_id;

  -- 8. Populate response and commit idempotency record
  UPDATE idempotency_records
    SET response_status = 200,
        response_body = :response_json
    WHERE id = :idemp_id
      AND organization_id = :org_id;

COMMIT;
```

---

### 5. Order Cancellation

Cancelling an order releases its active reservation back to available inventory (`ACTIVE → RELEASED`).

```sql
BEGIN;

  -- 1. Establish idempotency ownership / Wait-and-Replay
  INSERT INTO idempotency_records (
    id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, response_status, response_body, created_at, expires_at
  ) VALUES (
    :idemp_id, :org_id, 'USER', :principal_id, 'POST:/api/orders/{id}/cancel', :key, :hash, NULL, NULL, NOW(), NOW() + INTERVAL '7 days'
  ) ON CONFLICT (organization_id, principal_type, principal_id, operation_scope, idempotency_key) DO NOTHING
  RETURNING id;

  -- Ownership Detection & PostgreSQL READ COMMITTED Concurrency:
  -- (Consume result via query API, e.g., PreparedStatement.executeQuery() or Spring query API):
  -- - One returned row: this request inserted the idempotency record and owns the key. The returned ID identifies its record.
  --     Proceed to step 2 as primary executor.
  --     (If a competing transaction rolled back, this waiting insert unblocked and inserted its own row — no second INSERT required.)
  -- - No returned rows: the insert did not occur because the unique-key conflict was handled by DO NOTHING.
  --     Follow the duplicate/replay path and read the existing committed record with row lock:
  --       SELECT * FROM idempotency_records
  --         WHERE organization_id = :org_id AND principal_type = 'USER' AND principal_id = :principal_id
  --           AND operation_scope = 'POST:/api/orders/{id}/cancel' AND idempotency_key = :key
  --         FOR UPDATE;
  --       * If request_hash matches and response_status IS NOT NULL: replay cached response and ROLLBACK.
  --       * If request_hash differs: return HTTP 409 Conflict and ROLLBACK.

  -- 2. Lock order and reservation
  SELECT * FROM orders WHERE id = :order_id AND organization_id = :org_id FOR UPDATE;
  SELECT * FROM reservations WHERE order_id = :order_id AND organization_id = :org_id AND status = 'ACTIVE' FOR UPDATE;

  -- 3. Lock inventory balances in canonical order: product_id ASC, warehouse_id ASC
  SELECT * FROM inventory_balances
    WHERE organization_id = :org_id
      AND (product_id, warehouse_id) IN (
        SELECT product_id, warehouse_id FROM reservation_allocations
        WHERE reservation_id = :reservation_id AND organization_id = :org_id
      )
    ORDER BY product_id ASC, warehouse_id ASC
    FOR UPDATE;

  -- 4. Release reserved holds (on_hand untouched)
  UPDATE inventory_balances
    SET reserved = reserved - :quantity,
        updated_at = NOW()
    WHERE organization_id = :org_id
      AND product_id = :product_id
      AND warehouse_id = :warehouse_id;

  -- 5. Record transaction and ledger entries (reserved_delta = -qty, on_hand_delta = 0)
  INSERT INTO inventory_transactions (
    id, organization_id, transaction_type, reference_type, reference_id, notes, actor_user_id, created_at
  ) VALUES (
    :tx_id, :org_id, 'RELEASE', 'ORDER', :order_id, :notes, :user_id, NOW()
  );

  INSERT INTO inventory_ledger_entries (
    id, transaction_id, organization_id, warehouse_id, product_id, entry_number,
    on_hand_delta, reserved_delta, created_at
  ) VALUES (
    :entry_id, :tx_id, :org_id, :warehouse_id, :product_id, 1,
    0, -:quantity, NOW()
  );

  -- 6. Transition reservation to terminal state RELEASED
  UPDATE reservations SET status = 'RELEASED', updated_at = NOW() WHERE id = :reservation_id AND organization_id = :org_id;

  -- 7. Transition order to terminal state CANCELLED
  UPDATE orders SET status = 'CANCELLED', updated_at = NOW() WHERE id = :order_id AND organization_id = :org_id;

  -- 8. Populate response and commit idempotency record
  UPDATE idempotency_records
    SET response_status = 200,
        response_body = :response_json
    WHERE id = :idemp_id
      AND organization_id = :org_id;

COMMIT;
```

---

### 6. Reservation Expiration (Background Worker)

A scheduled background worker releases expired active reservations for pending orders.

```sql
BEGIN;

  -- 1. Select and lock expired PENDING orders first to preserve deterministic lock hierarchy:
  --    (Locking orders before reservations matches the lock order in Order Confirmation and Cancellation,
  --     preventing lock-order reversal deadlocks. SKIP LOCKED ensures non-blocking worker concurrency.)
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

  -- 2. For each locked order, acquire lock on its active reservation:
  SELECT id AS reservation_id, status, expires_at
    FROM reservations
    WHERE order_id = :order_id
      AND organization_id = :org_id
    FOR UPDATE;

  -- Post-lock validation:
  -- Re-verify that order.status == 'PENDING', reservation.status == 'ACTIVE', and reservation.expires_at <= NOW().
  -- If a concurrent confirmation or cancellation won the race before the order lock was acquired,
  -- order.status will no longer be 'PENDING' -> safely skip this order without mutating balances.

  -- 3. Lock balance rows in canonical order (product_id ASC, warehouse_id ASC)
  SELECT * FROM inventory_balances
    WHERE organization_id = :org_id
      AND (product_id, warehouse_id) IN (
        SELECT product_id, warehouse_id FROM reservation_allocations
        WHERE reservation_id = :res_id AND organization_id = :org_id
      )
    ORDER BY product_id ASC, warehouse_id ASC
    FOR UPDATE;

  -- 4. Release reserved quantity (on_hand unchanged, reserved decreased)
  UPDATE inventory_balances
    SET reserved = reserved - :allocated_qty,
        updated_at = NOW()
    WHERE organization_id = :org_id
      AND product_id = :product_id
      AND warehouse_id = :warehouse_id;

  -- 5. Append ledger entries (reserved_delta = -qty, on_hand_delta = 0)
  INSERT INTO inventory_transactions (
    id, organization_id, transaction_type, reference_type, reference_id, notes, actor_user_id, created_at
  ) VALUES (
    :tx_id, :org_id, 'RELEASE', 'ORDER', :order_id, 'Expired pending reservation released', NULL, NOW()
  );

  INSERT INTO inventory_ledger_entries (
    id, transaction_id, organization_id, warehouse_id, product_id, entry_number,
    on_hand_delta, reserved_delta, created_at
  ) VALUES (
    :entry_id, :tx_id, :org_id, :warehouse_id, :product_id, 1,
    0, -:allocated_qty, NOW()
  );

  -- 6. Transition reservation to terminal state EXPIRED
  UPDATE reservations SET status = 'EXPIRED', updated_at = NOW() WHERE id = :res_id AND organization_id = :org_id;

  -- 7. Transition order to terminal state EXPIRED
  UPDATE orders SET status = 'EXPIRED', updated_at = NOW() WHERE id = :order_id AND organization_id = :org_id;

COMMIT;
```

> [!NOTE]
> - Confirmed orders (`o.status = 'CONFIRMED'`) are excluded by the query and their active reservations **do not** expire.
> - Once in `EXPIRED`, `CONSUMED`, or `RELEASED`, a reservation is in a terminal state and cannot return to `ACTIVE`.
> - Allocation records (`reservation_allocations`) are never deleted upon reservation expiration, fulfillment, or cancellation; `reservations.status` controls whether inventory is actively held, while allocation rows permanently preserve historical allocation traceability.

---

### 7. Warehouse Transfer

Transfers stock between warehouses. Instant and atomic in MVP. The schema supports multiple line items (`transfer_items`) under a single transfer header. Reserved stock cannot be transferred.

```sql
BEGIN;

  -- 1. Establish idempotency ownership / Wait-and-Replay
  INSERT INTO idempotency_records (
    id, organization_id, principal_type, principal_id, operation_scope, idempotency_key, request_hash, response_status, response_body, created_at, expires_at
  ) VALUES (
    :idemp_id, :org_id, 'USER', :principal_id, 'POST:/api/transfers', :key, :hash, NULL, NULL, NOW(), NOW() + INTERVAL '7 days'
  ) ON CONFLICT (organization_id, principal_type, principal_id, operation_scope, idempotency_key) DO NOTHING
  RETURNING id;

  -- Ownership Detection & PostgreSQL READ COMMITTED Concurrency:
  -- (Consume result via query API, e.g., PreparedStatement.executeQuery() or Spring query API):
  -- - One returned row: this request inserted the idempotency record and owns the key. The returned ID identifies its record.
  --     Proceed to step 2 as primary executor.
  --     (If a competing transaction rolled back, this waiting insert unblocked and inserted its own row — no second INSERT required.)
  -- - No returned rows: the insert did not occur because the unique-key conflict was handled by DO NOTHING.
  --     Follow the duplicate/replay path and read the existing committed record with row lock:
  --       SELECT * FROM idempotency_records
  --         WHERE organization_id = :org_id AND principal_type = 'USER' AND principal_id = :principal_id
  --           AND operation_scope = 'POST:/api/transfers' AND idempotency_key = :key
  --         FOR UPDATE;
  --       * If request_hash matches and response_status IS NOT NULL: replay cached response and ROLLBACK.
  --       * If request_hash differs: return HTTP 409 Conflict and ROLLBACK.

  -- 2. Pre-create all missing destination balance rows before locking begins:
  --    (Ensures destination balance rows exist for all transfer items so SELECT ... FOR UPDATE acquires real row locks)
  INSERT INTO inventory_balances (
    id, organization_id, product_id, warehouse_id, on_hand, reserved, created_at, updated_at
  ) VALUES
    (:dest_bal_id_1, :org_id, :product_id_1, :destination_warehouse_id, 0, 0, NOW(), NOW()),
    (:dest_bal_id_2, :org_id, :product_id_2, :destination_warehouse_id, 0, 0, NOW(), NOW())
  ON CONFLICT (organization_id, product_id, warehouse_id) DO NOTHING;

  -- 3. Acquire all affected balance locks across all transfer items in ONE GLOBAL CANONICAL ORDER:
  --    (Global canonical lock order: product_id ASC, warehouse_id ASC across all products and both warehouses)
  --    CRITICAL RULE: Do NOT acquire locks product-by-product in arbitrary request order.
  SELECT * FROM inventory_balances
    WHERE organization_id = :org_id
      AND product_id IN (:transfer_product_ids)
      AND warehouse_id IN (:source_warehouse_id, :destination_warehouse_id)
    ORDER BY product_id ASC, warehouse_id ASC
    FOR UPDATE;

  -- 4. Verify source available stock for each transfer item:
  --    For each line (product_id, quantity):
  --      Assert: (source.on_hand - source.reserved) >= :quantity
  --      Reserved inventory cannot be transferred. If ANY line fails, ROLLBACK.

  -- 5. Update balances for each transfer item (deduct source, credit destination):
  --    Line 1:
  UPDATE inventory_balances
    SET on_hand = on_hand - :qty_1, updated_at = NOW()
    WHERE organization_id = :org_id AND warehouse_id = :source_warehouse_id AND product_id = :product_id_1;

  UPDATE inventory_balances
    SET on_hand = on_hand + :qty_1, updated_at = NOW()
    WHERE organization_id = :org_id AND warehouse_id = :destination_warehouse_id AND product_id = :product_id_1;

  --    Line 2:
  UPDATE inventory_balances
    SET on_hand = on_hand - :qty_2, updated_at = NOW()
    WHERE organization_id = :org_id AND warehouse_id = :source_warehouse_id AND product_id = :product_id_2;

  UPDATE inventory_balances
    SET on_hand = on_hand + :qty_2, updated_at = NOW()
    WHERE organization_id = :org_id AND warehouse_id = :destination_warehouse_id AND product_id = :product_id_2;

  -- 6. Insert transfer records (MVP transfers are atomic and immediately completed; no status column)
  INSERT INTO transfers (id, organization_id, source_warehouse_id, destination_warehouse_id, notes, actor_user_id, created_at, completed_at)
    VALUES (:transfer_id, :org_id, :source_warehouse_id, :destination_warehouse_id, :notes, :user_id, NOW(), NOW());

  INSERT INTO transfer_items (id, organization_id, transfer_id, product_id, quantity, created_at)
    VALUES
      (:item_id_1, :org_id, :transfer_id, :product_id_1, :qty_1, NOW()),
      (:item_id_2, :org_id, :transfer_id, :product_id_2, :qty_2, NOW());

  -- 7. Insert inventory transaction header
  INSERT INTO inventory_transactions (
    id, organization_id, transaction_type, reference_type, reference_id, notes, actor_user_id, created_at
  ) VALUES (
    :tx_id, :org_id, 'TRANSFER', 'TRANSFER', :transfer_id, :notes, :user_id, NOW()
  );

  -- 8. Append paired dual-delta ledger entries for each transfer item:
  --    Line 1 OUT (source warehouse):
  INSERT INTO inventory_ledger_entries (
    id, transaction_id, organization_id, warehouse_id, product_id, entry_number,
    on_hand_delta, reserved_delta, created_at, transfer_item_id, transfer_direction
  ) VALUES (
    :entry_src_1, :tx_id, :org_id, :source_warehouse_id, :product_id_1, 1,
    -:qty_1, 0, NOW(), :item_id_1, 'OUT'
  );

  --    Line 1 IN (destination warehouse):
  INSERT INTO inventory_ledger_entries (
    id, transaction_id, organization_id, warehouse_id, product_id, entry_number,
    on_hand_delta, reserved_delta, created_at, transfer_item_id, transfer_direction
  ) VALUES (
    :entry_dst_1, :tx_id, :org_id, :destination_warehouse_id, :product_id_1, 2,
    +:qty_1, 0, NOW(), :item_id_1, 'IN'
  );

  --    Line 2 OUT (source warehouse):
  INSERT INTO inventory_ledger_entries (
    id, transaction_id, organization_id, warehouse_id, product_id, entry_number,
    on_hand_delta, reserved_delta, created_at, transfer_item_id, transfer_direction
  ) VALUES (
    :entry_src_2, :tx_id, :org_id, :source_warehouse_id, :product_id_2, 3,
    -:qty_2, 0, NOW(), :item_id_2, 'OUT'
  );

  --    Line 2 IN (destination warehouse):
  INSERT INTO inventory_ledger_entries (
    id, transaction_id, organization_id, warehouse_id, product_id, entry_number,
    on_hand_delta, reserved_delta, created_at, transfer_item_id, transfer_direction
  ) VALUES (
    :entry_dst_2, :tx_id, :org_id, :destination_warehouse_id, :product_id_2, 4,
    +:qty_2, 0, NOW(), :item_id_2, 'IN'
  );

  -- 9. Populate response and commit idempotency record with tenant scoping
  UPDATE idempotency_records
    SET response_status = 200,
        response_body = :response_json
    WHERE id = :idemp_id
      AND organization_id = :org_id;

COMMIT;
```

#### Transfer Transaction Verification Guarantees (Database vs. Application)

* **Database Schema Constraints Enforce:**
  - `chk_inventory_ledger_entries_transfer_pair`: Guarantees `transfer_item_id` and `transfer_direction` are both populated or both NULL.
  - `chk_inventory_ledger_entries_transfer_direction`: Restricts directions strictly to `'OUT'` and `'IN'`.
  - `chk_inventory_ledger_entries_transfer_rules`: Enforces that `'OUT'` entries have `on_hand_delta < 0` and `reserved_delta = 0`, while `'IN'` entries have `on_hand_delta > 0` and `reserved_delta = 0`.
  - Partial unique indexes (`idx_ledger_transfer_out`, `idx_ledger_transfer_in`): Enforce **at most one** `OUT` and **at most one** `IN` entry per transfer line item.
  - Composite FKs: Guarantee tenant and entity integrity between ledger entries, products, warehouses, transactions, and transfer items.
* **Application Transaction Logic Must Verify (Before Commit):**
  1. All missing destination balance rows are pre-created before locking begins.
  2. All affected balance locks across all transfer products and both warehouses are acquired in **one global canonical order** (`product_id ASC, warehouse_id ASC`), strictly preventing product-by-product lock-order reversal deadlocks.
  3. The associated inventory transaction has `transaction_type = 'TRANSFER'`.
  4. Its reference type and reference ID correctly identify the corresponding transfer header (`reference_type = 'TRANSFER'`, `reference_id = transfers.id`).
  5. Every transfer line item belongs to that transfer header (`transfer_items.transfer_id = transfers.id`).
  6. The ledger entries, transfer header, transfer line items, and inventory transaction all share the identical `organization_id`.
  7. Exactly one `OUT` entry and exactly one `IN` entry exist for every transfer line item ($2 \times N$ entries for $N$ line items).
  8. Both entries for an item reference the exact same `transfer_item_id`.
  9. The `OUT` entry specifies `warehouse_id = transfers.source_warehouse_id`.
  10. The `IN` entry specifies `warehouse_id = transfers.destination_warehouse_id`.
  11. Both entries specify the identical `product_id` matching `transfer_items.product_id`.
  12. Deltas are equal in magnitude and opposite in sign: `on_hand_delta = -transfer_items.quantity` for `OUT`, and `on_hand_delta = +transfer_items.quantity` for `IN`.
  13. Both reserved deltas are strictly zero (`reserved_delta = 0`).
  14. Source warehouse has sufficient available stock ($\mathtt{on\_hand - reserved \ge quantity}$) for every line prior to deduction.
  15. All verification steps, balance updates, transfer records, and ledger entries succeed atomically in the same database transaction before commit.

---

## Unresolved Architectural & Transactional Decisions

The project explicitly defers the following six architectural decisions:

| # | Decision | Architectural Context | Status |
|---|---|---|---|
| 1 | **JWT vs. Session Authentication** | Token-based stateless authentication vs. server-side session store with cookie-based session identifiers for REST API consumers. | **Deferred** |
| 2 | **Role-to-Permission Mapping** | Exact granular permission strings (e.g., `inventory:read`, `inventory:adjust`, `reservations:create`) vs. coarse role checks (`ADMIN`, `OPERATOR`, `AUDITOR`) in `@PreAuthorize`. | **Deferred** |
| 3 | **Reservation TTL Configuration & Default Duration** | Configurable per-organization/per-order TTL vs. static application-wide default (e.g., 15 minutes, 30 minutes); dynamic extension via API. | **Deferred** |
| 4 | **Deadlock Retry & Backoff Policy** | Maximum retry attempts (e.g., 3), backoff multiplier (exponential vs. linear), jitter formula, and Spring `@Retryable` configuration for PostgreSQL `40P01` deadlock recovery. | **Deferred** |
| 5 | **Inter-Module Communication Strategy** | Direct synchronous Java interface calls within the modular monolith vs. Spring Application Events (`ApplicationEventPublisher` / `@TransactionalEventListener`) vs. domain event bus. | **Deferred** |
| 6 | **HTTP Timeout Handling During In-Progress Idempotency Transaction** | Client retry handling when an HTTP client times out while the server transaction is actively executing (under atomic transactions, uncommitted rows are never durable and retry attempts either wait on the active lock or proceed upon rollback). | **Deferred** |

### Future Implementation Considerations

The following operational and implementation tuning considerations are noted for ongoing production calibration:
- **Transaction Lock Timeout Duration:** Evaluation of `SET LOCAL lock_timeout` (e.g., 3s, 5s) per session/transaction to fail fast under extreme concurrency contention.
- **Reservation Expiration Polling Cadence & Batch Size:** Tuning background worker scheduling frequency (e.g., 15s vs. 60s) and batch size (e.g., 50 vs. 200) for `FOR UPDATE SKIP LOCKED` cleanup.
- **Historical Ledger & Audit Log Partitioning:** Volume thresholds and partitioning strategy (e.g., PostgreSQL range partitioning by `created_at`) for append-only tables `inventory_ledger_entries` and `audit_logs`.
