# Data Dictionary

> **Status: PRELIMINARY DESIGN** — All tables and columns described here represent the preliminary database design for STOCKGUARD. It is subject to architectural review and validation. No tables, DDL scripts, or Flyway migrations have been created yet.

---

## Design Patterns & Cross-Cutting Rules

### Organization-Aware Scoping
STOCKGUARD is built with an **organization-aware schema**. While initial deployment operates with a single organization, the schema enforces organization boundaries at all levels:
- **Global User Identity:** The `users` table models global user identity and authentication credentials; it does **not** contain an `organization_id`.
- **Tenant Membership & Role:** The `organization_memberships` table links a global `user` to an `organization` and contains the organization-specific `role` column (`ADMIN`, `MANAGER`, `OPERATOR`, `VIEWER`).
- **Organization-Owned Business Records:** Every business table (`products`, `warehouses`, `inventory_balances`, `inventory_transactions`, `inventory_ledger_entries`, `orders`, `order_items`, `reservations`, `reservation_allocations`, `transfers`, `transfer_items`, `idempotency_records`, `audit_logs`) includes an `organization_id` foreign key referencing `organizations(id)`.
- **Enforcement of Organization Isolation:**
  1. *Relational Schema Layer:* Composite foreign keys (e.g., `(organization_id, warehouse_id) → warehouses(organization_id, id)`) guarantee that parent-child relationships cannot cross organization boundaries at the PostgreSQL schema layer.
  2. *Query & Data Layer:* All application repositories, JDBC templates, and background workers enforce mandatory tenant predicates (`WHERE organization_id = :org_id`) on all queries and mutations.
  3. *Security Context Layer:* Authentication resolves the active organization membership from `organization_memberships`, injecting the validated `organization_id` into the request security context.
- **MVP Scope Boundary:** The MVP is **not** a fully implemented multi-tenant SaaS platform. Self-service tenant registration, tenant billing, custom domains, and SaaS cross-tenant administrative tooling are out of scope.

### Composite Foreign Keys
To guarantee data isolation at the relational level, child tables reference parent tables via composite foreign keys that include `organization_id` (e.g., `inventory_balances(organization_id, product_id)` → `products(organization_id, id)`).

### Immutable Append-Only Stores
`inventory_ledger_entries` and `audit_logs` are immutable audit stores. No `UPDATE` or `DELETE` operations are permitted, and no `updated_at` column exists.

---

## Table Scope: MVP Boundary vs. Deferred Extensions

The preliminary database design defines 19 tables in total, organized into **16 planned MVP tables** and **3 deferred future extensions**.

### Planned MVP Tables (16 Tables)

1. `organizations`
2. `users`
3. `organization_memberships`
4. `products`
5. `warehouses`
6. `inventory_balances`
7. `inventory_transactions`
8. `inventory_ledger_entries`
9. `orders`
10. `order_items`
11. `reservations`
12. `reservation_allocations`
13. `transfers`
14. `transfer_items`
15. `idempotency_records`
16. `audit_logs`

### Deferred Extensions (3 Tables)

17. `membership_roles` — Deferred; MVP uses a direct `role` column on `organization_memberships`.
18. `system_settings` — Deferred; MVP uses Spring Boot `application.yml` and environment variables.
19. `api_clients` — Deferred; deferred unless machine-to-machine integrations are a day-one launch requirement.

---

## Table Definitions — MVP Scope (16 Tables)

### 1. `organizations`
**Purpose:** Top-level tenant entity. All business data is partitioned by organization.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique organization identifier |
| `name` | `VARCHAR` | `NOT NULL` | Display name of organization |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Row creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

**Relationships:** Parent of all organization-scoped tables.

---

### 2. `users`
**Purpose:** System user identity accounts decoupled from organizations.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique user identity identifier |
| `email` | `VARCHAR` | `NOT NULL, UNIQUE` | Global login email address |
| `password_hash` | `VARCHAR` | `NOT NULL` | Hashed password credential |
| `active` | `BOOLEAN` | `NOT NULL` | Soft-deletion flag |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Account creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

**Relationships:** Linked to `organizations` via `organization_memberships`. Referenced by `audit_logs` and `inventory_transactions(created_by)`.

---

### 3. `organization_memberships`
**Purpose:** Maps a user to an organization and assigns their role in the MVP.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique membership identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `user_id` | `UUID` | `FK → users(id), NOT NULL` | User account |
| `role` | `VARCHAR` | `NOT NULL` | Assigned role: `ADMIN`, `MANAGER`, `OPERATOR`, `VIEWER` |
| `active` | `BOOLEAN` | `NOT NULL` | Active membership flag |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Membership creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

**Unique constraint:** `(organization_id, user_id)`

---

### 4. `products`
**Purpose:** Catalog entity. Each product has a normalized SKU unique within its organization. **No separate `skus` table exists in MVP.**

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique product identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `sku` | `VARCHAR` | `NOT NULL` | Normalized SKU code, unique within organization |
| `name` | `VARCHAR` | `NOT NULL` | Product name |
| `description` | `TEXT` | — | Optional extended product description |
| `active` | `BOOLEAN` | `NOT NULL` | Active product flag |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

**SKU Normalization & Uniqueness Policy:**
- Leading and trailing whitespace is trimmed: `TRIM(sku)`.
- Case is normalized to uppercase: `UPPER(TRIM(sku))`.
- Equivalent values (`" widget-100 "`, `"Widget-100"`) resolve to canonical `"WIDGET-100"`.
- Uniqueness is organization-scoped.
- Enforced at database level via functional unique index `UNIQUE(organization_id, UPPER(TRIM(sku)))` and check constraint `CHECK (sku = UPPER(TRIM(sku)) AND length(sku) > 0)`.

**Unique constraints:**
- `(organization_id, sku)`
- `(organization_id, id)` (target for composite foreign keys referencing products)

**Relationships:** Belongs to `organizations`. Referenced by `inventory_balances`, `inventory_ledger_entries`, `order_items`, `reservation_allocations`, `transfer_items`.

---

### 5. `warehouses`
**Purpose:** Storage facilities holding inventory. Configured with allocation priority rank and warehouse code tie-breaker.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique warehouse identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `code` | `VARCHAR` | `NOT NULL` | Normalized code (e.g. `WH-EAST`); deterministic tie-breaker |
| `name` | `VARCHAR` | `NOT NULL` | Warehouse display name |
| `location` | `VARCHAR` | — | Address or region description |
| `priority` | `INTEGER` | `NOT NULL` | Allocation priority rank (lower integer = higher priority) |
| `active` | `BOOLEAN` | `NOT NULL` | Active warehouse flag |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

**Warehouse Code Normalization & Uniqueness Policy:**
- Leading and trailing whitespace is trimmed: `TRIM(code)`.
- Case is normalized to uppercase: `UPPER(TRIM(code))`.
- Rejection of empty values: `length(code) > 0`.
- Uniqueness is organization-scoped.
- Enforced at database level via functional unique index `UNIQUE(organization_id, UPPER(TRIM(code)))` and check constraint `CHECK (code = UPPER(TRIM(code)) AND length(code) > 0)`.

**Unique constraints:**
- `(organization_id, code)`
- `(organization_id, id)` (target for composite foreign keys referencing warehouses)

**Relationships:** Belongs to `organizations`. Referenced by `inventory_balances`, `inventory_ledger_entries`, `reservation_allocations`, `transfers`.

---

### 6. `inventory_balances`
**Purpose:** Real-time stock counts (`on_hand` and `reserved`) per product per warehouse.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique balance identifier |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `warehouse_id` | `UUID` | `NOT NULL` | Warehouse facility |
| `product_id` | `UUID` | `NOT NULL` | Product tracked |
| `on_hand` | `BIGINT` | `NOT NULL, CHECK (on_hand >= 0)` | Physical stock count |
| `reserved` | `BIGINT` | `NOT NULL, CHECK (reserved >= 0)` | Logical stock committed to active reservations |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

**Unique constraint:** `(organization_id, warehouse_id, product_id)`\
**Table Check Constraint:** `CHECK (reserved <= on_hand)`\
**Composite FKs:**
- `(organization_id, warehouse_id)` → `warehouses(organization_id, id)`
- `(organization_id, product_id)` → `products(organization_id, id)`

**Balance Semantics & Derived Availability:**
- `on_hand` and `reserved` are the sole authoritative stored balance values.
- Available stock is strictly derived at query time:
  $$\text{available} = \text{on\_hand} - \text{reserved}$$
  `available` is **not** an independently mutable stored column.
- Any generated column or database view is strictly a read-only convenience and is non-authoritative.
- Enforced invariants: $\mathtt{on\_hand \ge 0}$, $\mathtt{reserved \ge 0}$, and $\mathtt{reserved \le on\_hand}$, ensuring $\mathtt{available \ge 0}$ unconditionally holds.

---

### 7. `inventory_transactions`
**Purpose:** Logical grouping header for one or more atomic inventory ledger entries.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique transaction identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `transaction_type` | `VARCHAR` | `NOT NULL` | `RECEIVE`, `ADJUST`, `RESERVE`, `RELEASE`, `FULFILL`, `TRANSFER`, `OPENING_BALANCE` |
| `reference_type` | `VARCHAR` | — | Originating entity category (`ORDER`, `TRANSFER`, `MANUAL`) |
| `reference_id` | `UUID` | — | Primary key of the originating entity |
| `notes` | `TEXT` | — | Audit explanation or reason |
| `created_by` | `UUID` | — | User ID that initiated the transaction |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Transaction timestamp |

**Unique constraint:** `(organization_id, id)` (target for composite foreign keys referencing inventory_transactions)

**Relationships:** Belongs to `organizations`. Parent of `inventory_ledger_entries`.

---

### 8. `inventory_ledger_entries`
**Purpose:** Append-only ledger recording signed `on_hand_delta` and `reserved_delta` changes.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique ledger entry identifier |
| `transaction_id` | `UUID` | `NOT NULL` | Parent grouping transaction |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `warehouse_id` | `UUID` | `NOT NULL` | Affected warehouse |
| `product_id` | `UUID` | `NOT NULL` | Affected product |
| `entry_type` | `VARCHAR` | `NOT NULL` | `RECEIVE`, `ADJUST`, `RESERVE`, `RELEASE`, `FULFILL`, `TRANSFER_OUT`, `TRANSFER_IN`, `OPENING_BALANCE` |
| `on_hand_delta` | `BIGINT` | `NOT NULL` | Signed physical delta (+ receipt/transfer-in, - fulfillment/transfer-out, 0 reservation) |
| `reserved_delta` | `BIGINT` | `NOT NULL` | Signed hold delta (+ reserve, - release/fulfillment/expiration, 0 adjustment) |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Entry timestamp |

**Composite FKs:**
- `(organization_id, transaction_id)` → `inventory_transactions(organization_id, id)`
- `(organization_id, warehouse_id)` → `warehouses(organization_id, id)`
- `(organization_id, product_id)` → `products(organization_id, id)`

**Mathematical Reconciliation:**
$$\text{on\_hand} = \sum \mathtt{on\_hand\_delta}, \quad \text{reserved} = \sum \mathtt{reserved\_delta}$$

---

### 9. `orders`
**Purpose:** Commercial orders tracking customer commitments across their independent lifecycle.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique order identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `order_number` | `VARCHAR` | `NOT NULL` | Human-readable order number |
| `status` | `VARCHAR` | `NOT NULL` | Lifecycle: `PENDING`, `CONFIRMED`, `FULFILLING`, `FULFILLED`, `CANCELLED`, `EXPIRED` |
| `customer_reference` | `VARCHAR` | — | External customer ID or PO number |
| `total_amount` | `NUMERIC(19,4)` | — | Total monetary value |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Order creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

**Unique constraints:**
- `(organization_id, order_number)`
- `(organization_id, id)` (target for composite foreign keys referencing orders)

---

### 10. `order_items`
**Purpose:** Line items within an order. Captures historical point-in-time snapshots of SKU, product name, and unit price.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique line item identifier |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `order_id` | `UUID` | `NOT NULL` | Parent order |
| `product_id` | `UUID` | `NOT NULL` | Product ordered |
| `sku_snapshot` | `VARCHAR` | `NOT NULL` | Point-in-time normalized SKU snapshot |
| `name_snapshot` | `VARCHAR` | `NOT NULL` | Point-in-time product name snapshot |
| `quantity` | `BIGINT` | `NOT NULL, CHECK (quantity > 0)` | Ordered quantity |
| `unit_price` | `NUMERIC(19,4)` | `NOT NULL, CHECK (unit_price >= 0)` | Point-in-time unit price snapshot |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Line creation timestamp |

**Unique constraints:**
- `(organization_id, id)` (target for composite foreign keys referencing order_items)
- `(organization_id, order_id, id, product_id)` (target for composite foreign key from reservation_allocations)

**Composite FKs:**
- `(organization_id, order_id)` → `orders(organization_id, id)`
- `(organization_id, product_id)` → `products(organization_id, id)`

---

### 11. `reservations`
**Purpose:** Tracks inventory holds committed to an order. Operates on an independent 4-stage lifecycle.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique reservation identifier |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `order_id` | `UUID` | `NOT NULL` | Associated order |
| `status` | `VARCHAR` | `NOT NULL` | Lifecycle: `ACTIVE`, `CONSUMED`, `RELEASED`, `EXPIRED` |
| `expires_at` | `TIMESTAMPTZ` | — | Expiration timestamp for unconfirmed reservations |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Reservation creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

**Unique constraints:**
- `(organization_id, id)` (target for composite foreign keys referencing reservations)
- `(organization_id, order_id)` (enforces the MVP rule of one reservation per order)
- `(organization_id, id, order_id)` (target for composite foreign key from reservation_allocations)

**Composite FK:** `(organization_id, order_id)` → `orders(organization_id, id)`\
**Lifecycle Rules:**
- Reservations possess strictly 4 states: `ACTIVE`, `CONSUMED`, `RELEASED`, `EXPIRED`. (Never use order states for reservations).
- An active reservation held by a `PENDING` order has an `expires_at` timestamp and can expire.
- A confirmed order's active reservation does **not** automatically expire (`expires_at` is set to `NULL`).
- Terminal states (`CONSUMED`, `RELEASED`, `EXPIRED`) cannot transition back to `ACTIVE`.

---

### 12. `reservation_allocations`
**Purpose:** Maps held inventory to specific warehouses, products, and order items.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique allocation identifier |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `reservation_id` | `UUID` | `NOT NULL` | Parent reservation |
| `order_id` | `UUID` | `NOT NULL` | Associated order |
| `order_item_id` | `UUID` | `NOT NULL` | Associated order line item |
| `warehouse_id` | `UUID` | `NOT NULL` | Warehouse fulfilling allocation |
| `product_id` | `UUID` | `NOT NULL` | Product allocated |
| `quantity` | `BIGINT` | `NOT NULL, CHECK (quantity > 0)` | Quantity allocated from warehouse |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Allocation timestamp |

**Composite FKs:**
- `(organization_id, reservation_id, order_id)` → `reservations(organization_id, id, order_id)`
- `(organization_id, order_id, order_item_id, product_id)` → `order_items(organization_id, order_id, id, product_id)`
- `(organization_id, warehouse_id)` → `warehouses(organization_id, id)`
- `(organization_id, product_id)` → `products(organization_id, id)`

**Relational Integrity & Historical Allocation Preservation:**
- **Organization, Order, and Product Integrity:** The composite foreign keys enforce complete relational consistency across tenant, order, reservation, and catalog boundaries at the PostgreSQL schema layer:
  - `(organization_id, reservation_id, order_id) REFERENCES reservations(organization_id, id, order_id)` guarantees that an allocation belongs to the exact same organization and parent order as the referenced reservation.
  - `(organization_id, order_id, order_item_id, product_id) REFERENCES order_items(organization_id, order_id, id, product_id)` guarantees that an allocation belongs to the exact same organization and parent order as the referenced order item, and that the allocated `product_id` strictly matches the product requested on that order item.
  - Because `organization_id` and `order_id` are co-located on `reservation_allocations` and participate in both foreign keys, an allocation cannot cross organization or order boundaries; an allocation cannot reference an order item belonging to a different order than its reservation.
  - `(organization_id, warehouse_id) REFERENCES warehouses(organization_id, id)` and `(organization_id, product_id) REFERENCES products(organization_id, id)` preserve warehouse and product tenant integrity.
- **Order Item Quantity Verification:** For each order item, successful reservation creation must transactionally verify that the sum of allocated quantities across all assigned warehouses exactly equals the order item's requested quantity:
  $$\sum \mathtt{reservation\_allocations.quantity} = \mathtt{order\_items.quantity}$$
  Because standard PostgreSQL table-level `CHECK` constraints cannot execute subqueries or evaluate cross-row aggregations, this aggregate invariant is enforced at the transaction level within the reservation domain service logic before committing.
- **Historical Allocation Preservation:** Allocation records remain permanently available after a reservation transitions to `CONSUMED`, `RELEASED`, or `EXPIRED`. Allocation rows are never deleted upon state transition.
- **Hold Semantics:** The parent `reservations.status` is the sole determinant of whether stock is actively held (`status = 'ACTIVE'`). When a reservation transitions to a terminal state (`CONSUMED`, `RELEASED`, or `EXPIRED`), inventory is physically deducted or released from `inventory_balances.reserved`, but allocation rows are preserved to maintain full historical auditability of warehouse commitments.

---

### 13. `transfers`
**Purpose:** Tracks stock movement between warehouses. Modeled as atomic and immediate in MVP.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique transfer identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `source_warehouse_id` | `UUID` | `NOT NULL` | Origin warehouse |
| `destination_warehouse_id` | `UUID` | `NOT NULL` | Destination warehouse |
| `status` | `VARCHAR` | `NOT NULL` | `COMPLETED` (immediate atomic transfer) |
| `notes` | `TEXT` | — | Reason for transfer |
| `created_by` | `UUID` | — | User initiating transfer |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Transfer timestamp |

**Unique constraint:** `(organization_id, id)` (target for composite foreign keys referencing transfers)\
**Composite FKs:**
- `(organization_id, source_warehouse_id)` → `warehouses(organization_id, id)`
- `(organization_id, destination_warehouse_id)` → `warehouses(organization_id, id)`\
**Table Check Constraint:** `CHECK (source_warehouse_id != destination_warehouse_id)`

---

### 14. `transfer_items`
**Purpose:** Line items within a warehouse transfer.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique line identifier |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `transfer_id` | `UUID` | `NOT NULL` | Parent transfer |
| `product_id` | `UUID` | `NOT NULL` | Product transferred |
| `quantity` | `BIGINT` | `NOT NULL, CHECK (quantity > 0)` | Quantity relocated |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Line creation timestamp |

**Composite FKs:**
- `(organization_id, transfer_id)` → `transfers(organization_id, id)`
- `(organization_id, product_id)` → `products(organization_id, id)`

---

### 15. `idempotency_records`
**Purpose:** Prevents duplicate mutation operations across distributed clients and retries.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique record identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `principal_id` | `UUID` | `NOT NULL` | Authenticated user ID |
| `operation_scope` | `VARCHAR` | `NOT NULL` | Endpoint or action scope (e.g. `POST:/api/reservations`) |
| `idempotency_key` | `VARCHAR` | `NOT NULL` | Client-provided uniqueness key |
| `request_hash` | `VARCHAR` | `NOT NULL` | SHA-256 hash of method, path, and payload |
| `status` | `VARCHAR` | `NOT NULL DEFAULT 'IN_PROGRESS'` | Execution status: `IN_PROGRESS`, `COMPLETED` |
| `response_status` | `INTEGER` | — | Cached HTTP status code (populated on completion) |
| `response_body` | `JSONB` | — | Cached JSON response body (populated on completion) |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Timestamp of processing start |
| `expires_at` | `TIMESTAMPTZ` | `NOT NULL` | Expiration cutoff (default: 7 days) |

**Unique constraint:** `(organization_id, principal_id, operation_scope, idempotency_key)`\
**Concurrency & Lifecycle Semantics:**
- Same key & same payload: returns original cached response.
- Same key & different payload: returns HTTP `409 Conflict`.
- Concurrent duplicates (same key & payload): second request waits on row lock / unique constraint for the first transaction to complete; upon successful commit, second request replays the stored response without re-executing business mutations.
- Rollback & safe retry: if first transaction rolls back, PostgreSQL's MVCC removes the uncommitted record, allowing a subsequent request or retry to safely proceed and execute the business operation.
- Transactional consistency: creation, business operation, and response persistence occur in the same database transaction.
- Client disconnect post-commit safety: if a client drops connection or experiences a network timeout after the database transaction has committed, the business operation has already executed and the response is safely stored in `idempotency_records`. A subsequent retry with the same key and payload replays the committed response without duplicate execution.
- Retention & cleanup requirement: 7 days initial design policy. Due to the unique constraint on `(organization_id, principal_id, operation_scope, idempotency_key)`, expired records must be pruned/cleaned up by an asynchronous background worker before a unique key can be safely reused.

---

### 16. `audit_logs`
**Purpose:** Append-only record of administrative, system, and user actions.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique audit log identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `user_id` | `UUID` | — | User who performed action (nullable for system workers) |
| `action` | `VARCHAR` | `NOT NULL` | Action code (e.g., `RECEIVE_STOCK`, `CONFIRM_ORDER`) |
| `entity_type` | `VARCHAR` | `NOT NULL` | Domain model name (e.g., `order`, `product`) |
| `entity_id` | `UUID` | — | Primary key of affected entity |
| `details` | `JSONB` | — | Contextual delta or parameter payload |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Immutable creation timestamp |

---

## Table Definitions — Deferred Extensions (3 Tables)

### 17. `membership_roles` (Deferred)
**Purpose:** Normalizes multiple granular roles per membership.\
**MVP Strategy:** Deferred. In the MVP, authorization is captured directly by the `role` column on `organization_memberships`.\
**Condition for Inclusion:** When organizations require tenant-defined custom roles or multi-role assignments.

---

### 18. `system_settings` (Deferred)
**Purpose:** Dynamic in-database key-value store for operational settings.\
**MVP Strategy:** Deferred. In the MVP, operational parameters are managed via Spring Boot `application.yml` and environment variables.\
**Condition for Inclusion:** When tenant administrators need in-dashboard runtime configuration editing without application restarts.

---

### 19. `api_clients` (Deferred)
**Purpose:** Machine-to-machine API credentials for headless programmatic access.\
**MVP Strategy:** Deferred. Initial MVP access is scoped to interactive user sessions via the React dashboard.\
**Exact Product Requirement for MVP Inclusion:** Moves to MVP if and only if automated third-party machine integrations (e.g. ERP sync, marketplace connectors, or B2B webhook consumers) are mandated as a launch requirement requiring non-interactive API key / token authentication without a user session.
