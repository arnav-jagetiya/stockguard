# Database Design

> **Status: PRELIMINARY DESIGN** — This document describes the preliminary database schema design for STOCKGUARD. It is prepared for architectural review and validation. No tables, DDL scripts, or Flyway migrations have been created.

## Accepted Architectural Decisions

| Decision | Reference | Summary |
|---|---|---|
| PostgreSQL as authoritative data store | ADR-002 | PostgreSQL is the single source of truth for all application state. |
| Single `products` table with normalized SKU | — | Products are modeled in a single catalog table with an organization-unique normalized SKU; no separate variant or SKU entity in MVP. |
| UUID primary keys | — | All entities use `UUID` primary keys for global uniqueness and safe distributed generation. |
| `BIGINT` for inventory quantities | — | `on_hand`, `reserved`, and delta columns use `BIGINT` to prevent integer overflow. |
| `NUMERIC(19,4)` for monetary values | — | Monetary columns use fixed-precision `NUMERIC(19,4)` to prevent floating-point rounding errors. |
| Composite foreign keys | — | Multi-column foreign keys enforce organization boundaries at the schema layer. |
| Append-only inventory ledger | ADR-004 | The `inventory_ledger_entries` table is insert-only, providing an immutable audit trail of inventory mutations. |

## Multi-Organization Scope & Boundaries

STOCKGUARD is designed with an **organization-aware schema**. While initial deployment operates with a single organization, the schema incorporates multi-tenant boundary protections from day one:
- **Global User Identity:** The `users` table models global user identity and authentication credentials; it does **not** contain an `organization_id`.
- **Tenant Membership & Role:** The `organization_memberships` table links a global `user` to an `organization` and contains the organization-specific `role` column (`ADMIN`, `MANAGER`, `OPERATOR`, `VIEWER`).
- **Organization-Owned Business Records:** Every business table (`products`, `warehouses`, `inventory_balances`, `inventory_transactions`, `inventory_ledger_entries`, `orders`, `order_items`, `reservations`, `reservation_allocations`, `transfers`, `transfer_items`, `idempotency_records`, `audit_logs`) includes an `organization_id` foreign key referencing `organizations(id)`.
- **Enforcement of Organization Isolation:**
  1. *Relational Schema Layer:* Composite foreign keys (e.g., `(organization_id, warehouse_id) → warehouses(organization_id, id)`) guarantee that parent-child relationships cannot cross organization boundaries at the PostgreSQL schema layer.
  2. *Query & Data Layer:* All application repositories, JDBC templates, and background workers enforce mandatory tenant predicates (`WHERE organization_id = :org_id`) on all queries and mutations.
  3. *Security Context Layer:* Authentication resolves the active organization membership from `organization_memberships`, injecting the validated `organization_id` into the request security context.
- **MVP Scope Boundary:** The MVP is **not** a fully implemented multi-tenant SaaS platform. Self-service tenant registration, tenant billing/metering, custom domain routing, and cross-tenant SaaS administrative portals are explicitly out of scope for the MVP.

## Schema Management

- **Migration tool:** Flyway (accepted)
- **Strategy:** Versioned SQL migration scripts (`V1__...`) checked into version control.

---

## Table Scope: MVP Boundary vs. Deferred Extensions

The preliminary database architecture identifies 19 tables in total, partitioned strictly into **16 planned MVP tables** and **3 deferred future extensions**.

### Planned MVP Tables (16 Tables)

These 16 tables constitute the complete database footprint planned for initial MVP Flyway migrations:

1. `organizations` — Multi-tenant root partition; isolates data across all tenants.
2. `users` — System identity accounts and authentication credentials.
3. `organization_memberships` — Associates users with organizations; carries a direct `role` column (`ADMIN`, `MANAGER`, `OPERATOR`, `VIEWER`) for MVP RBAC.
4. `products` — Product catalog with an organization-unique normalized SKU; no variants in MVP.
5. `warehouses` — Storage facilities with allocation priority rank and normalized warehouse code.
6. `inventory_balances` — Real-time stored positions (`on_hand`, `reserved`); availability is strictly derived.
7. `inventory_transactions` — Logical transaction headers grouping atomic ledger entries.
8. `inventory_ledger_entries` — Append-only dual-delta ledger (`on_hand_delta`, `reserved_delta`).
9. `orders` — Commercial orders with 6-stage lifecycle (`PENDING`, `CONFIRMED`, `FULFILLING`, `FULFILLED`, `CANCELLED`, `EXPIRED`).
10. `order_items` — Line items with historical snapshots (`sku_snapshot`, `name_snapshot`, `unit_price`).
11. `reservations` — Inventory holds with 4-stage lifecycle (`ACTIVE`, `CONSUMED`, `RELEASED`, `EXPIRED`).
12. `reservation_allocations` — Warehouse-level breakdown of stock allocated to an active reservation.
13. `transfers` — Warehouse-to-warehouse stock relocation header.
14. `transfer_items` — Itemized line quantities for stock transfers.
15. `idempotency_records` — Scoped idempotency store with 7-day retention and concurrency serialization.
16. `audit_logs` — Immutable append-only audit trail for administrative and operational actions.

### Deferred Tables (3 Future Extensions)

The following 3 tables are documented as part of the broader preliminary design but are **explicitly deferred from initial MVP migrations**:

17. **`membership_roles` (Deferred to Post-MVP):**
    - *Rationale:* For the MVP, a direct `role VARCHAR(50) NOT NULL` column on `organization_memberships` (with fixed values `ADMIN`, `MANAGER`, `OPERATOR`, `VIEWER`) satisfies all authorization requirements. A separate join table adds unnecessary join overhead and complexity.
    - *Condition for Inclusion:* Defer until custom tenant-defined dynamic permission matrices or multi-role memberships become a concrete product requirement.
18. **`system_settings` (Deferred to Post-MVP):**
    - *Rationale:* System configuration parameters (such as reservation TTL, idempotency retention, reconciliation frequency) are more reliably managed via Spring Boot external configuration files (`application.yml` and environment variables). Introducing a database settings store requires runtime cache-invalidation logic that is premature for MVP.
    - *Condition for Inclusion:* Defer until tenant-administrators require runtime in-dashboard configuration changes without redeploying or restarting the application.
19. **`api_clients` (Deferred to Post-MVP):**
    - *Rationale:* MVP access is initially scoped to human operators and administrators interacting through the React dashboard via user session authentication.
    - *Exact Product Requirement for MVP Inclusion:* `api_clients` will move into the MVP if and only if **external machine-to-machine integrations** (such as external third-party ERPs, automated marketplace inventory synchronization workers, or B2B webhook dispatchers) are mandated as a day-one launch requirement requiring programmatic API key / token authentication without an interactive user session.

---

## Detailed Table Schemas — MVP Scope (16 Tables)

### 1. `organizations`

Root tenant boundary entity. Every business record belongs to an organization.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique organization identifier |
| `name` | `VARCHAR` | `NOT NULL` | Display name of the organization |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Row creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

---

### 2. `users`

Global identity accounts. Decoupled from organizations to support multi-organization memberships.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique user identifier |
| `email` | `VARCHAR` | `NOT NULL, UNIQUE` | Global login email address |
| `password_hash` | `VARCHAR` | `NOT NULL` | Hashed password (bcrypt / argon2) |
| `active` | `BOOLEAN` | `NOT NULL` | Soft-deletion / active status flag |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Row creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

---

### 3. `organization_memberships`

Associates users with organizations. In the MVP, role-based authorization is captured directly via the `role` column.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique membership identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Associated organization |
| `user_id` | `UUID` | `FK → users(id), NOT NULL` | Associated user identity |
| `role` | `VARCHAR` | `NOT NULL` | MVP role: `ADMIN`, `MANAGER`, `OPERATOR`, `VIEWER` |
| `active` | `BOOLEAN` | `NOT NULL` | Whether this membership is active |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Row creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

**Unique constraint:** `(organization_id, user_id)`

---

### 4. `products`

Catalog entity. Each product represents a single stockable item with a normalized SKU unique within its organization. **No separate `skus` table exists in MVP.**

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique product identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `sku` | `VARCHAR` | `NOT NULL` | Normalized SKU code, unique within org |
| `name` | `VARCHAR` | `NOT NULL` | Product display name |
| `description` | `TEXT` | — | Optional product description |
| `active` | `BOOLEAN` | `NOT NULL` | Active status flag |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Row creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

**Unique constraints:**
- `(organization_id, sku)`
- `(organization_id, id)` (target for composite foreign keys referencing products)

#### SKU Normalization & Uniqueness Policy

To prevent inconsistent application-side handling and protect data integrity:
1. **Trimming:** All leading and trailing whitespace is stripped (`TRIM(sku)`).
2. **Case Normalization:** SKUs are converted to canonical uppercase (`UPPER(TRIM(sku))`).
3. **Treatment of Equivalent Values:** Variations such as `" widget-100 "`, `"Widget-100"`, `"WIDGET-100 "`, and `"widget-100"` are treated as equivalent and resolved to `"WIDGET-100"`.
4. **Organization-Scoped Uniqueness:** Uniqueness is scoped to the organization (`UNIQUE(organization_id, normalized_sku)`).
5. **Database-Level Enforcement:** The database schema guarantees normalized uniqueness via two complementary mechanisms:
   - A functional unique index:
     ```sql
     CREATE UNIQUE INDEX idx_products_org_normalized_sku
       ON products (organization_id, UPPER(TRIM(sku)));
     ```
   - A check constraint ensuring that stored column values conform to the normalized representation:
     ```sql
     ALTER TABLE products
       ADD CONSTRAINT chk_products_sku_normalized
       CHECK (sku = UPPER(TRIM(sku)) AND length(sku) > 0);
     ```

---

### 5. `warehouses`

Physical storage facilities. Configured with allocation priority rank and normalized warehouse code.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique warehouse identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `code` | `VARCHAR` | `NOT NULL` | Normalized warehouse code (e.g. `WH-EAST`); tie-breaker |
| `name` | `VARCHAR` | `NOT NULL` | Warehouse display name |
| `location` | `VARCHAR` | — | Physical address or region description |
| `priority` | `INTEGER` | `NOT NULL` | Allocation priority rank (lower integer = higher priority) |
| `active` | `BOOLEAN` | `NOT NULL` | Whether the warehouse is active |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Row creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

**Unique constraints:**
- `(organization_id, code)`
- `(organization_id, id)` (target for composite foreign keys referencing warehouses)

#### Warehouse Code Normalization & Uniqueness Policy

To ensure deterministic warehouse identification and tie-breaking:
1. **Trimming:** All leading and trailing whitespace is stripped (`TRIM(code)`).
2. **Case Normalization:** Warehouse codes are converted to canonical uppercase (`UPPER(TRIM(code))`).
3. **Rejection of Empty Values:** Empty strings or whitespace-only values are strictly rejected (`length(code) > 0`).
4. **Organization-Scoped Uniqueness:** Warehouse codes are unique within an organization (`UNIQUE(organization_id, normalized_code)`).
5. **Database-Level Enforcement:** Consistent with the product SKU strategy, the schema guarantees normalized uniqueness via two complementary database mechanisms:
   - A functional unique index:
     ```sql
     CREATE UNIQUE INDEX idx_warehouses_org_normalized_code
       ON warehouses (organization_id, UPPER(TRIM(code)));
     ```
   - A check constraint ensuring that stored column values conform to the normalized representation:
     ```sql
     ALTER TABLE warehouses
       ADD CONSTRAINT chk_warehouses_code_normalized
       CHECK (code = UPPER(TRIM(code)) AND length(code) > 0);
     ```

---

### 6. `inventory_balances`

Current inventory position per product per warehouse.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique balance record identifier |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `warehouse_id` | `UUID` | `NOT NULL` | Warehouse facility |
| `product_id` | `UUID` | `NOT NULL` | Product tracked |
| `on_hand` | `BIGINT` | `NOT NULL, CHECK (on_hand >= 0)` | Physical inventory count |
| `reserved` | `BIGINT` | `NOT NULL, CHECK (reserved >= 0)` | Quantity committed to active reservations |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

**Unique constraint:** `(organization_id, warehouse_id, product_id)`\
**Composite FKs:**
- `(organization_id, warehouse_id)` → `warehouses(organization_id, id)`
- `(organization_id, product_id)` → `products(organization_id, id)`\
**Table Check Constraint:** `CHECK (reserved <= on_hand)`

#### Balance Semantics & Derived Availability

- **Stored Columns:** `on_hand` and `reserved` are the **sole authoritative stored balance quantities**.
- **Derived Availability:** Available inventory is strictly derived:
  $$\text{available} = \text{on\_hand} - \text{reserved}$$
  `available` is **not** an independently mutable stored column. It cannot be directly updated or written to.
- **Generated Column or View:** If a generated column (`available BIGINT GENERATED ALWAYS AS (on_hand - reserved) STORED`) or a convenience database view (`inventory_balances_view`) is deployed for query ergonomics, it is strictly read-only and non-authoritative; `on_hand` and `reserved` remain the sole authoritative stored state.
- **Invariants:** The check constraints enforce:
  1. $\mathtt{on\_hand \ge 0}$
  2. $\mathtt{reserved \ge 0}$
  3. $\mathtt{reserved \le on\_hand}$\
  These constraints guarantee that $\mathtt{available \ge 0}$ holds at all times.

---

### 7. `inventory_transactions`

Logical transaction header grouping atomic inventory ledger entries (e.g., receiving batches, reservations, fulfillments, transfers).

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique transaction identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `transaction_type` | `VARCHAR` | `NOT NULL` | `RECEIVE`, `ADJUST`, `RESERVE`, `RELEASE`, `FULFILL`, `TRANSFER`, `OPENING_BALANCE` |
| `reference_type` | `VARCHAR` | — | Originating entity category (`ORDER`, `TRANSFER`, `MANUAL`) |
| `reference_id` | `UUID` | — | Primary key of the originating entity |
| `notes` | `TEXT` | — | Audit description or operator explanation |
| `created_by` | `UUID` | — | User ID that triggered the operation |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Transaction timestamp |

**Unique constraint:** `(organization_id, id)` (target for composite foreign keys referencing inventory_transactions)

---

### 8. `inventory_ledger_entries`

Immutable, append-only ledger recording every balance modification with dual deltas.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique ledger entry identifier |
| `transaction_id` | `UUID` | `NOT NULL` | Parent transaction |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `warehouse_id` | `UUID` | `NOT NULL` | Affected warehouse |
| `product_id` | `UUID` | `NOT NULL` | Affected product |
| `entry_type` | `VARCHAR` | `NOT NULL` | `RECEIVE`, `ADJUST`, `RESERVE`, `RELEASE`, `FULFILL`, `TRANSFER_IN`, `TRANSFER_OUT`, `OPENING_BALANCE` |
| `on_hand_delta` | `BIGINT` | `NOT NULL` | Signed change in `on_hand` |
| `reserved_delta` | `BIGINT` | `NOT NULL` | Signed change in `reserved` |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Immutable entry timestamp |

**Composite FKs:**
- `(organization_id, transaction_id)` → `inventory_transactions(organization_id, id)`
- `(organization_id, warehouse_id)` → `warehouses(organization_id, id)`
- `(organization_id, product_id)` → `products(organization_id, id)`

---

### 9. `orders`

Commercial order entity tracking customer commitments across their independent lifecycle.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique order identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `order_number` | `VARCHAR` | `NOT NULL` | Human-readable order number |
| `status` | `VARCHAR` | `NOT NULL` | Lifecycle: `PENDING`, `CONFIRMED`, `FULFILLING`, `FULFILLED`, `CANCELLED`, `EXPIRED` |
| `customer_reference`| `VARCHAR` | — | External customer identifier or PO number |
| `total_amount` | `NUMERIC(19,4)` | — | Total monetary value |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Row creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

**Unique constraints:**
- `(organization_id, order_number)`
- `(organization_id, id)` (target for composite foreign keys referencing orders)

---

### 10. `order_items`

Line items for an order. Captures immutable historical snapshots of SKU, product name, and price at time of order creation.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique line item identifier |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `order_id` | `UUID` | `NOT NULL` | Parent order |
| `product_id` | `UUID` | `NOT NULL` | Product ordered |
| `sku_snapshot` | `VARCHAR` | `NOT NULL` | Point-in-time normalized SKU snapshot |
| `name_snapshot` | `VARCHAR` | `NOT NULL` | Point-in-time product name snapshot |
| `quantity` | `BIGINT` | `NOT NULL, CHECK (quantity > 0)` | Ordered quantity |
| `unit_price` | `NUMERIC(19,4)` | `NOT NULL, CHECK (unit_price >= 0)` | Point-in-time price snapshot |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Row creation timestamp |

**Unique constraints:**
- `(organization_id, id)` (target for composite foreign keys referencing order_items)
- `(organization_id, order_id, id, product_id)` (target for composite foreign key from reservation_allocations)

**Composite FKs:**
- `(organization_id, order_id)` → `orders(organization_id, id)`
- `(organization_id, product_id)` → `products(organization_id, id)`

---

### 11. `reservations`

Tracks temporary inventory commitments. Operates on an independent 4-stage lifecycle.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique reservation identifier |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `order_id` | `UUID` | `NOT NULL` | Associated order |
| `status` | `VARCHAR` | `NOT NULL` | Lifecycle: `ACTIVE`, `CONSUMED`, `RELEASED`, `EXPIRED` |
| `expires_at` | `TIMESTAMPTZ` | — | Timestamp after which an unconfirmed pending reservation expires |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Row creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

**Unique constraints:**
- `(organization_id, id)` (target for composite foreign keys referencing reservations)
- `(organization_id, order_id)` (enforces the MVP rule of one successful reservation per order)
- `(organization_id, id, order_id)` (target for composite foreign key from reservation_allocations)

**Composite FK:** `(organization_id, order_id)` → `orders(organization_id, id)`

> [!IMPORTANT]
> **Reservation Lifecycle Rules:**
> - Never use `PENDING`, `CONFIRMED`, `FULFILLED`, or `CANCELLED` as reservation statuses.
> - While an order is `PENDING`, its active reservation carries an `expires_at` timestamp and can expire.
> - When an associated order transitions to `CONFIRMED`, its active reservation does **not** automatically expire (`expires_at` is cleared to `NULL`).
> - Fulfilling an order consumes the reservation (`ACTIVE → CONSUMED`).
> - Cancelling an order releases the reservation (`ACTIVE → RELEASED`).
> - Terminal states (`CONSUMED`, `RELEASED`, `EXPIRED`) cannot transition back to `ACTIVE`.

---

### 12. `reservation_allocations`

Maps reserved quantities to specific warehouses, products, and order items.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique allocation identifier |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `reservation_id` | `UUID` | `NOT NULL` | Parent reservation |
| `order_id` | `UUID` | `NOT NULL` | Associated order |
| `order_item_id` | `UUID` | `NOT NULL` | Associated order line item |
| `warehouse_id` | `UUID` | `NOT NULL` | Warehouse fulfilling this portion |
| `product_id` | `UUID` | `NOT NULL` | Product reserved |
| `quantity` | `BIGINT` | `NOT NULL, CHECK (quantity > 0)` | Quantity allocated from warehouse |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Row creation timestamp |

**Composite FKs:**
- `(organization_id, reservation_id, order_id)` → `reservations(organization_id, id, order_id)`
- `(organization_id, order_id, order_item_id, product_id)` → `order_items(organization_id, order_id, id, product_id)`
- `(organization_id, warehouse_id)` → `warehouses(organization_id, id)`
- `(organization_id, product_id)` → `products(organization_id, id)`

#### Relational Integrity & Historical Allocation Preservation
- **Organization, Order, and Product Integrity:** The composite foreign keys enforce complete relational consistency across tenant, order, reservation, and catalog boundaries at the PostgreSQL schema layer:
  - `(organization_id, reservation_id, order_id) REFERENCES reservations(organization_id, id, order_id)` guarantees that an allocation belongs to the exact same organization and parent order as the referenced reservation.
  - `(organization_id, order_id, order_item_id, product_id) REFERENCES order_items(organization_id, order_id, id, product_id)` guarantees that an allocation belongs to the exact same organization and parent order as the referenced order item, and that the allocated `product_id` strictly matches the product specified on the order item.
  - Because `organization_id` and `order_id` are co-located on `reservation_allocations` and participate in both foreign keys, an allocation cannot cross organization or order boundaries; an allocation cannot reference an order item belonging to a different order than its reservation.
  - Existing warehouse and product foreign keys `(organization_id, warehouse_id) REFERENCES warehouses(organization_id, id)` and `(organization_id, product_id) REFERENCES products(organization_id, id)` preserve facility and catalog tenant integrity.
- **Order Item Quantity Verification:** For each order item, successful reservation creation must transactionally verify that the sum of allocated quantities across all assigned warehouses exactly equals the order item's requested quantity:
  $$\sum \mathtt{reservation\_allocations.quantity} = \mathtt{order\_items.quantity}$$
  Because PostgreSQL table-level `CHECK` constraints can only evaluate single-row expressions and cannot evaluate cross-row aggregations, this aggregate invariant is enforced at the transaction level within the reservation service logic.
- **Preserved History:** `reservation_allocations` records remain permanently available after a reservation transitions to `CONSUMED`, `RELEASED`, or `EXPIRED`. Allocation rows are never deleted upon state transition.
- **Hold Control:** The parent `reservations.status` is the sole determinant of whether inventory is actively held (`status = 'ACTIVE'`). When a reservation transitions to a terminal state (`CONSUMED`, `RELEASED`, or `EXPIRED`), inventory is physically deducted or released from `inventory_balances.reserved`, but allocation rows are preserved to maintain a complete historical record of warehouse commitments.

---

### 13. `transfers`

Records stock relocation between two warehouses within the same organization.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique transfer identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `source_warehouse_id` | `UUID` | `NOT NULL` | Origin warehouse |
| `destination_warehouse_id` | `UUID` | `NOT NULL` | Destination warehouse |
| `status` | `VARCHAR` | `NOT NULL` | `COMPLETED` (MVP transfers are atomic & immediate) |
| `notes` | `TEXT` | — | Reason or reference for the transfer |
| `created_by` | `UUID` | — | User ID that initiated transfer |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Row creation timestamp |

**Unique constraint:** `(organization_id, id)` (target for composite foreign keys referencing transfers)

**Composite FKs:**
- `(organization_id, source_warehouse_id)` → `warehouses(organization_id, id)`
- `(organization_id, destination_warehouse_id)` → `warehouses(organization_id, id)`\
**Table Check Constraint:** `CHECK (source_warehouse_id != destination_warehouse_id)`

---

### 14. `transfer_items`

Line items for stock transfers.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique transfer line identifier |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `transfer_id` | `UUID` | `NOT NULL` | Parent transfer |
| `product_id` | `UUID` | `NOT NULL` | Product transferred |
| `quantity` | `BIGINT` | `NOT NULL, CHECK (quantity > 0)` | Quantity transferred |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Row creation timestamp |

**Composite FKs:**
- `(organization_id, transfer_id)` → `transfers(organization_id, id)`
- `(organization_id, product_id)` → `products(organization_id, id)`

---

### 15. `idempotency_records`

PostgreSQL-backed store ensuring at-most-once execution of mutating API operations.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique record identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `principal_id` | `UUID` | `NOT NULL` | Authenticated user ID |
| `operation_scope` | `VARCHAR` | `NOT NULL` | Endpoint or operation identifier (e.g. `POST:/api/reservations`) |
| `idempotency_key` | `VARCHAR` | `NOT NULL` | Client-provided uniqueness key |
| `request_hash` | `VARCHAR` | `NOT NULL` | SHA-256 hash of method, path, and body |
| `status` | `VARCHAR` | `NOT NULL DEFAULT 'IN_PROGRESS'` | Execution state: `IN_PROGRESS`, `COMPLETED` |
| `response_status` | `INTEGER` | — | Cached HTTP status code (populated on completion) |
| `response_body` | `JSONB` | — | Serialized JSON response payload (populated on completion) |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Record creation timestamp |
| `expires_at` | `TIMESTAMPTZ` | `NOT NULL` | Expiration cutoff (default: 7 days) |

**Unique constraint:** `(organization_id, principal_id, operation_scope, idempotency_key)`

#### Idempotency Concurrency & Transactional Semantics

- **Scoping:** Idempotency is strictly scoped by `(organization_id, principal_id, operation_scope, idempotency_key)`.
- **Same Key & Same Payload:** If a completed record exists with matching scope and identical `request_hash`, the server returns the cached response status and body without re-executing business logic.
- **Same Key & Different Payload:** If a record exists with matching scope but a differing `request_hash`, the server rejects the request with HTTP `409 Conflict`.
- **Concurrent Requests (Wait-and-Replay):**
  - When Request 1 starts, it inserts an idempotency record with `status = 'IN_PROGRESS'` within its database transaction.
  - If a concurrent Request 2 arrives with the exact same key and payload, Request 2 attempts to insert or lock the record and **waits** for Request 1's transaction to commit or roll back.
  - When Request 1 successfully commits (`status = 'COMPLETED'`), Request 2 unblocks, observes the completed record, verifies matching `request_hash`, and replays the cached response. Concurrent duplicates **never** execute the business operation twice.
- **Rollback & Safe Retry:**
  - If Request 1 encounters an error and its transaction rolls back, PostgreSQL's rollback eliminates the uncommitted `IN_PROGRESS` row.
  - A subsequent request or client retry can then immediately proceed, create a new record, and safely execute the business operation.
- **Transactional Consistency:** Idempotency record creation, business operations, and response persistence occur within the same atomic database transaction.
- **Client Disconnect Post-Commit Safety:** If a client connection drops or times out after the database transaction has successfully committed, the business mutation has already executed and the response is safely stored in `idempotency_records`. A subsequent retry with the same key and payload does not re-execute the business operation; it detects the committed record and immediately replays the cached response.
- **Proposed Retention & Cleanup Requirement:** Retained for **7 days** as an initial design policy. Due to the unique constraint on `(organization_id, principal_id, operation_scope, idempotency_key)`, expired records must be pruned/cleaned up by an asynchronous background job before a unique key can be safely reused.

---

### 16. `audit_logs`

Immutable append-only record of administrative, system, and user actions for compliance and auditability.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique log identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `user_id` | `UUID` | — | Acting user (nullable for background workers) |
| `action` | `VARCHAR` | `NOT NULL` | Action code (e.g., `RECEIVE_STOCK`, `CONFIRM_ORDER`) |
| `entity_type` | `VARCHAR` | `NOT NULL` | Domain entity affected (e.g., `order`, `product`) |
| `entity_id` | `UUID` | — | Primary key of the affected entity |
| `details` | `JSONB` | — | Contextual delta or parameter payload |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Immutable creation timestamp |

---

## Detailed Table Schemas — Deferred Extensions (3 Tables)

The following tables are documented for future architecture phases and will not be created in the initial MVP Flyway migration.

### 17. `membership_roles` (Deferred)

Join table allowing multiple custom roles and granular permission sets per membership.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique role assignment identifier |
| `membership_id` | `UUID` | `FK → organization_memberships(id), NOT NULL` | Owning membership |
| `role` | `VARCHAR` | `NOT NULL` | Role code |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Row creation timestamp |

**Unique constraint:** `(membership_id, role)`\
**MVP Strategy:** Deferred. MVP utilizes the direct `role` column on `organization_memberships`.

---

### 18. `system_settings` (Deferred)

Dynamic key-value configuration store for runtime operational settings.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique setting identifier |
| `organization_id` | `UUID` | — | Nullable (`NULL` = system-wide default, non-null = organization override) |
| `setting_key` | `VARCHAR` | `NOT NULL` | Configuration key (e.g., `reservation.ttl_seconds`) |
| `setting_value` | `VARCHAR` | `NOT NULL` | Serialized setting value |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Row creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

**Unique constraint:** `(organization_id, setting_key)`\
**MVP Strategy:** Deferred. MVP utilizes Spring Boot `application.yml` and environment variables.

---

### 19. `api_clients` (Deferred)

Machine-to-machine API credentials for external automated integrations.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique client identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `name` | `VARCHAR` | `NOT NULL` | Human-readable client label |
| `client_id` | `VARCHAR` | `NOT NULL, UNIQUE` | Public client identifier |
| `client_secret_hash` | `VARCHAR` | `NOT NULL` | Securely hashed client secret |
| `active` | `BOOLEAN` | `NOT NULL` | Whether the client credential is valid |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL` | Row creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL` | Last modification timestamp |

**MVP Strategy:** Deferred unless automated external machine integrations are mandated as a day-one launch requirement.

---

## Proposed Index Strategy

- **Foreign Keys:** Indexes on all single-column and multi-column foreign keys (`organization_id`, `warehouse_id`, `product_id`, `order_id`, `reservation_id`, etc.).
- **Normalized SKU Functional Index:**
  ```sql
  CREATE UNIQUE INDEX idx_products_org_normalized_sku
    ON products (organization_id, UPPER(TRIM(sku)));
  ```
- **Normalized Warehouse Code Functional Unique Index:**
  ```sql
  CREATE UNIQUE INDEX idx_warehouses_org_normalized_code
    ON warehouses (organization_id, UPPER(TRIM(code)));
  ```
- **Order Number Unique Index:** `UNIQUE(organization_id, order_number)`.
- **One Reservation Per Order Unique Index:** `UNIQUE(organization_id, order_id)`.
- **Reservation Allocation Parent Unique Indexes:**
  - `reservations(organization_id, id, order_id)` to support composite foreign key `(organization_id, reservation_id, order_id)`.
  - `order_items(organization_id, order_id, id, product_id)` to support composite foreign key `(organization_id, order_id, order_item_id, product_id)`.
- **Balances Canonical Lock Index:** Composite index on `inventory_balances(product_id, warehouse_id)` to optimize `SELECT ... FOR UPDATE` canonical lock acquisition.
- **Balances Tenant Unique Index:** `UNIQUE(organization_id, warehouse_id, product_id)`.
- **Active Reservation Expiration Partial Index:**
  ```sql
  CREATE INDEX idx_reservations_active_expires
    ON reservations (expires_at)
    WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;
  ```
- **Ledger Reconciliation Index:** Composite index on `inventory_ledger_entries(organization_id, warehouse_id, product_id)` to optimize mathematical reconciliation queries.
- **Idempotency Lookup Unique Index:** `UNIQUE(organization_id, principal_id, operation_scope, idempotency_key)`.
