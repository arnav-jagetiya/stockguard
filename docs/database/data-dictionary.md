# Data Dictionary

> **Status: READY FOR FINAL REVIEW — PENDING PROJECT OWNER SIGN-OFF** — All tables and columns described here represent the database schema design for STOCKGUARD Milestone 2, aligned with [DATABASE_SCHEMA_SPECIFICATION.md](DATABASE_SCHEMA_SPECIFICATION.md). No tables, DDL scripts, or Flyway migrations have been created yet.

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

### Integer Representation & Quantity Validation
PostgreSQL `BIGINT` is a signed 64-bit integer (`-9,223,372,036,854,775,808` to `+9,223,372,036,854,775,807`). It provides a larger numeric range than 32-bit integers, though arithmetic can still overflow if values exceed its representable range. The schema enforces domain boundaries using explicit database `CHECK` constraints:
- `on_hand` and `reserved` on `inventory_balances` are non-negative, enforced via `CHECK (on_hand >= 0)` and `CHECK (reserved >= 0)` (with `CHECK (reserved <= on_hand)`).
- Business quantity columns (`quantity` on `order_items`, `reservation_allocations`, and `transfer_items`) must be strictly positive where required, enforced via `CHECK (quantity > 0)`.
- Dual ledger movements (`on_hand_delta` and `reserved_delta` on `inventory_ledger_entries`) use signed values to record both positive and negative movements.
- Database CHECK constraints enforce value boundaries on stored columns, but do not eliminate arithmetic overflow if mathematical operations exceed `BIGINT` limits.

### Composite Foreign Keys
To guarantee data isolation at the relational level, child tables reference parent tables via composite foreign keys that include `organization_id` (e.g., `inventory_balances(organization_id, product_id)` → `products(organization_id, id)`).

### Immutable Append-Only Stores
`inventory_ledger_entries` and `audit_logs` are immutable audit stores. No `UPDATE` or `DELETE` operations are permitted, and no `updated_at` column exists.

---

## Table Scope: MVP Boundary vs. Deferred Extensions

The database specification defines 19 tables in total, organized into **16 planned MVP tables** and **3 deferred future extensions**.

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
| `name` | `VARCHAR(255)` | `NOT NULL` | Display name of organization |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Row creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Last modification timestamp |

**Relationships:** Parent of all organization-scoped tables.

---

### 2. `users`
**Purpose:** System user identity accounts decoupled from organizations.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique user identity identifier |
| `email` | `VARCHAR(255)` | `NOT NULL` | Global login email address (original input casing preserved) |
| `normalized_email` | `VARCHAR(255)` | `NOT NULL, UNIQUE` | Canonical lowercase trimmed email |
| `password_hash` | `VARCHAR(255)` | — | Hashed password credential string (nullable pending final resolution of the authentication architecture) |
| `active` | `BOOLEAN` | `NOT NULL DEFAULT TRUE` | Soft-deletion flag |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Account creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Last modification timestamp |

**Check Constraints:**
- `chk_users_normalized_email`: `CHECK (normalized_email = lower(trim(email)) AND length(normalized_email) > 0)`

**Unique index:** `normalized_email`

**Authentication Decision Status & Credential Nullability Policy:**
- Authentication strategy remains explicitly **unresolved** (Decision #1: *JWT versus session authentication*, along with external identity providers, OAuth2/OIDC, or local credentials).
- `password_hash` is specified as nullable (`VARCHAR(255) NULL`) to avoid prematurely committing to local password authentication.
- If an external identity provider (OAuth2 / OIDC / SSO) is adopted, `password_hash` remains `NULL`; if local password authentication is ratified, hashing algorithms (e.g., Argon2id or bcrypt) and validation rules will be adopted.

**Relationships:** Linked to `organizations` via `organization_memberships`.

---

### 3. `organization_memberships`
**Purpose:** Maps a user to an organization and assigns their role in the MVP.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique membership identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `user_id` | `UUID` | `FK → users(id), NOT NULL` | User account |
| `role` | `VARCHAR(50)` | `NOT NULL` | Assigned role: `ADMIN`, `MANAGER`, `OPERATOR`, `VIEWER` |
| `active` | `BOOLEAN` | `NOT NULL DEFAULT TRUE` | Active membership flag |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Membership creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Last modification timestamp |

**Check Constraints:**
- `chk_memberships_role`: `CHECK (role IN ('ADMIN', 'MANAGER', 'OPERATOR', 'VIEWER'))`

**Unique constraints:**
- `(organization_id, user_id)`
- `(organization_id, user_id)` (target for composite foreign keys referencing actor memberships)

**Supporting Indexes:**
- `idx_memberships_user`: `(user_id)` (supports child-side foreign key to `users`)

---

### 4. `products`
**Purpose:** Catalog entity. Each product has a normalized SKU unique within its organization. **No separate `skus` table exists in MVP.**

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique product identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `sku` | `VARCHAR(100)` | `NOT NULL` | SKU code (original input casing preserved) |
| `normalized_sku` | `VARCHAR(100)` | `NOT NULL` | Canonical uppercase trimmed SKU code, unique within organization |
| `name` | `VARCHAR(255)` | `NOT NULL` | Product name |
| `description` | `TEXT` | — | Optional extended product description |
| `active` | `BOOLEAN` | `NOT NULL DEFAULT TRUE` | Active product flag |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Last modification timestamp |

**Check Constraints:**
- `chk_products_sku_normalized`: `CHECK (normalized_sku = upper(trim(sku)) AND length(normalized_sku) > 0)`
- `chk_products_name_not_empty`: `CHECK (length(trim(name)) > 0)`

**SKU Normalization & Uniqueness Policy:**
- Original input casing preserved in `sku`.
- Canonical form stored in `normalized_sku`: trimmed, uppercase (`normalized_sku = upper(trim(sku))`).
- Equivalent values (`" widget-100 "`, `"Widget-100"`) resolve to canonical `"WIDGET-100"`.
- Uniqueness is organization-scoped on `(organization_id, normalized_sku)`.

**Unique constraints:**
- `uq_products_org_normalized_sku`: `(organization_id, normalized_sku)`
- `uq_products_org_id`: `(organization_id, id)` (target for composite foreign keys referencing products)

**Supporting Indexes:**
- `idx_products_org_name`: `(organization_id, name)` (supports catalog search by name)

**Relationships:** Belongs to `organizations`. Referenced by `inventory_balances`, `inventory_ledger_entries`, `order_items`, `reservation_allocations`, `transfer_items`.

---

### 5. `warehouses`
**Purpose:** Storage facilities holding inventory. Configured with allocation priority rank and warehouse code tie-breaker.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique warehouse identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `code` | `VARCHAR(50)` | `NOT NULL` | Warehouse code (original input preserved) |
| `normalized_code` | `VARCHAR(50)` | `NOT NULL` | Canonical uppercase trimmed code; deterministic tie-breaker |
| `name` | `VARCHAR(255)` | `NOT NULL` | Warehouse display name |
| `location` | `VARCHAR(255)` | — | Address or region description |
| `priority` | `INTEGER` | `NOT NULL` | Allocation priority rank (lower integer = higher priority) |
| `active` | `BOOLEAN` | `NOT NULL DEFAULT TRUE` | Active warehouse flag |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Last modification timestamp |

**Check Constraints:**
- `chk_warehouses_code_normalized`: `CHECK (normalized_code = upper(trim(code)) AND length(normalized_code) > 0)`
- `chk_warehouses_name_not_empty`: `CHECK (length(trim(name)) > 0)`

**Warehouse Code Normalization & Uniqueness Policy:**
- Original input preserved in `code`.
- Canonical uppercase trimmed representation in `normalized_code` (`normalized_code = upper(trim(code))`).
- Rejection of empty values: `length(normalized_code) > 0`.
- Uniqueness is organization-scoped on `(organization_id, normalized_code)`.

**Unique constraints:**
- `uq_warehouses_org_normalized_code`: `(organization_id, normalized_code)`
- `uq_warehouses_org_id`: `(organization_id, id)` (target for composite foreign keys referencing warehouses)

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
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Last modification timestamp |

**Unique constraint:** `(organization_id, product_id, warehouse_id)`\
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
- **Negative Stock Adjustment Decision (Approved):** Downward adjustments to `on_hand` that would cause `on_hand < reserved` (which would implicitly drive `available < 0`) are strictly rejected. Every stock adjustment transaction acquires a row lock (`SELECT ... FOR UPDATE`) on the target balance row, validates $\mathtt{on\_hand + \Delta_{on\_hand} \ge reserved}$, and immediately rolls back without writing balance mutations or ledger entries if the invariant is violated.

---

### 7. `inventory_transactions`
**Purpose:** Logical grouping header for one or more atomic inventory ledger entries.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique transaction identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `transaction_type` | `VARCHAR(50)` | `NOT NULL` | `OPENING_BALANCE`, `RECEIPT`, `ADJUSTMENT`, `RESERVATION`, `RELEASE`, `FULFILLMENT`, `TRANSFER` |
| `reference_type` | `VARCHAR(50)` | — | Originating entity category (`ORDER`, `TRANSFER`, `MANUAL`; NULL for standalone receipts/opening balances) |
| `reference_id` | `UUID` | — | Primary key of the originating entity (`orders.id`, `transfers.id`, or external supporting document UUID such as a stock-count sheet or discrepancy ticket; NULL when reference_type is NULL) |
| `notes` | `TEXT` | — | Audit explanation or reason |
| `actor_user_id` | `UUID` | — | User ID that initiated the transaction (nullable) |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Transaction timestamp |

**Check Constraints:**
- `chk_inventory_transactions_type`: `CHECK (transaction_type IN ('OPENING_BALANCE', 'RECEIPT', 'ADJUSTMENT', 'RESERVATION', 'RELEASE', 'FULFILLMENT', 'TRANSFER'))`
- `chk_inventory_transactions_reference_pair`: `CHECK ((reference_type IS NULL AND reference_id IS NULL) OR (reference_type IS NOT NULL AND reference_id IS NOT NULL AND reference_type IN ('ORDER', 'TRANSFER', 'MANUAL')))`

**Unique constraint:** `(organization_id, id)` (target for composite foreign keys referencing inventory_transactions)

**Composite FK:**
- `(organization_id, actor_user_id)` → `organization_memberships(organization_id, user_id)` (nullable FK)

**Supporting Indexes:**
- `idx_transactions_org_actor`: `(organization_id, actor_user_id)` (supports actor membership foreign key and operator transaction queries)
- `idx_transactions_ref`: `(organization_id, reference_type, reference_id)` (supports originating entity lookups)
- `idx_transactions_created`: `(organization_id, created_at DESC)` (supports audit timeline pagination)

**Reference Semantics & Manual Adjustment Target:**
- `reference_type` categorizes the originating business entity:
  - `'ORDER'`: Originates from commercial order operations (reservations, fulfillments, cancellations, expirations); `reference_id` references `orders.id`.
  - `'TRANSFER'`: Originates from stock relocation; `reference_id` references `transfers.id`.
  - `'MANUAL'`: Originates from manual stock adjustments (`POST /api/inventory/adjust`) supported by an external document. In this case, `reference_id` stores the UUID of that external supporting document (such as a physical stock-count sheet or discrepancy ticket). Note that `reference_id` does not declare a database foreign key, as external documents reside outside the database schema. If no external supporting document exists, both `reference_type` and `reference_id` are stored as `NULL`, with operator attribution captured in `actor_user_id` and the business justification recorded in `notes`. Audit records remain independently recorded in `audit_logs`.
- Standalone stock receipts (`RECEIPT`) and opening balances (`OPENING_BALANCE`) have no external purchasing entity table in the MVP and store `(NULL, NULL)` for the reference pair.

**Relationships:** Belongs to `organizations`. Parent of `inventory_ledger_entries`.

---

### 8. `inventory_ledger_entries`
**Purpose:** Append-only ledger recording signed `on_hand_delta` and `reserved_delta` changes.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique ledger entry identifier |
| `transaction_id` | `UUID` | `NOT NULL` | Parent grouping transaction |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `entry_number` | `INTEGER` | `NOT NULL, CHECK (entry_number > 0)` | Monotonic 1-based sequence within transaction |
| `product_id` | `UUID` | `NOT NULL` | Affected product |
| `warehouse_id` | `UUID` | `NOT NULL` | Affected warehouse |
| `on_hand_delta` | `BIGINT` | `NOT NULL` | Signed physical delta (+ receipt/transfer-in, - fulfillment/transfer-out, 0 reservation) |
| `reserved_delta` | `BIGINT` | `NOT NULL` | Signed hold delta (+ reserve, - release/fulfillment/expiration, 0 adjustment) |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Entry timestamp |
| `transfer_item_id` | `UUID` | — | Originating transfer item ID (nullable) |
| `transfer_direction` | `VARCHAR(10)` | — | Direction for transfer entries: `OUT` or `IN` (nullable) |

**Check Constraints:**
- `chk_inventory_ledger_entries_entry_number`: `CHECK (entry_number > 0)`
- `chk_inventory_ledger_entries_nonzero_delta`: `CHECK (on_hand_delta != 0 OR reserved_delta != 0)`
- `chk_inventory_ledger_entries_transfer_pair`: `CHECK ((transfer_item_id IS NULL AND transfer_direction IS NULL) OR (transfer_item_id IS NOT NULL AND transfer_direction IS NOT NULL))`
- `chk_inventory_ledger_entries_transfer_direction`: `CHECK (transfer_direction IS NULL OR transfer_direction IN ('OUT', 'IN'))`
- `chk_inventory_ledger_entries_transfer_rules`: `CHECK (transfer_direction IS NULL OR (transfer_direction = 'OUT' AND on_hand_delta < 0 AND reserved_delta = 0) OR (transfer_direction = 'IN' AND on_hand_delta > 0 AND reserved_delta = 0))`

**Unique constraint:**
- `(organization_id, transaction_id, entry_number)`

**Composite FKs:**
- `(organization_id, transaction_id)` → `inventory_transactions(organization_id, id)`
- `(organization_id, warehouse_id)` → `warehouses(organization_id, id)`
- `(organization_id, product_id)` → `products(organization_id, id)`
- `(organization_id, transfer_item_id, product_id)` → `transfer_items(organization_id, id, product_id)` (nullable FK)

**Supporting Indexes:**
- `idx_ledger_transfer_out`: `(organization_id, transfer_item_id) WHERE transfer_direction = 'OUT'` (unique partial)
- `idx_ledger_transfer_in`: `(organization_id, transfer_item_id) WHERE transfer_direction = 'IN'` (unique partial)
- `idx_ledger_org_transfer_item`: `(organization_id, transfer_item_id, product_id) WHERE transfer_item_id IS NOT NULL` (supports composite foreign key to `transfer_items`)
- `idx_ledger_reconciliation`: `(organization_id, product_id, warehouse_id, created_at)`
- `idx_ledger_org_warehouse`: `(organization_id, warehouse_id)` (supports warehouse foreign key and facility audits)

**Migration Dependency & Creation-Order Clarification:**
- `inventory_ledger_entries` contains a composite foreign key referencing `transfer_items(organization_id, id, product_id)`.
- In Flyway migrations, either `transfers` and `transfer_items` must be created prior to `inventory_ledger_entries`, or the foreign key constraint `fk_inventory_ledger_entries_transfer_item` must be added via a subsequent `ALTER TABLE inventory_ledger_entries ADD CONSTRAINT ...` statement after `transfer_items` and its parent unique constraint `UNIQUE (organization_id, id, product_id)` have been instantiated.

**Transfer Ledger Pairing Invariant & Application Verification Guarantees:**
- Partial unique indexes on `(organization_id, transfer_item_id)` (`WHERE transfer_direction = 'OUT'` and `WHERE transfer_direction = 'IN'`) guarantee at the database level that **at most one** `OUT` and **at most one** `IN` entry can exist per transfer line item. They prevent duplicate directional entries but do not independently guarantee that *both* paired entries exist or that they are balanced.
- The application transaction boundary strictly verifies before commit that:
  1. Exactly one `OUT` entry and exactly one `IN` entry are created for every transfer line item.
  2. Both entries reference the identical `transfer_item_id`.
  3. The `OUT` entry specifies `warehouse_id = transfers.source_warehouse_id`.
  4. The `IN` entry specifies `warehouse_id = transfers.destination_warehouse_id`.
  5. Both entries specify the identical `product_id` matching `transfer_items.product_id`.
  6. Equal magnitude opposite sign deltas are recorded (`on_hand_delta = -transfer_items.quantity` for `OUT`, `+transfer_items.quantity` for `IN`).
  7. `reserved_delta = 0` for both entries.
  8. The associated inventory transaction header has `transaction_type = 'TRANSFER'`.
  9. The transaction header's reference type and reference ID correctly identify the corresponding transfer header (`reference_type = 'TRANSFER'`, `reference_id = transfers.id`).
  10. The transfer line item belongs to that transfer header (`transfer_items.transfer_id = transfers.id`).
  11. The ledger entries, transfer header, transfer line item, and inventory transaction all belong to the identical `organization_id`.
  12. Source warehouse has sufficient available stock ($\mathtt{on\_hand - reserved \ge quantity}$) prior to deduction.
  13. All verification steps, ledger entries, and balance updates succeed atomically within the same database transaction before commit.

**Mathematical Reconciliation:**
$$\text{on\_hand} = \sum \mathtt{on\_hand\_delta}, \quad \text{reserved} = \sum \mathtt{reserved\_delta}$$

---

### 9. `orders`
**Purpose:** Commercial orders tracking customer commitments across their independent lifecycle.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique order identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `order_number` | `VARCHAR(100)` | `NOT NULL` | Human-readable order number |
| `status` | `VARCHAR(50)` | `NOT NULL` | Lifecycle: `PENDING`, `CONFIRMED`, `FULFILLING`, `FULFILLED`, `CANCELLED`, `EXPIRED` |
| `customer_reference` | `VARCHAR(255)` | — | External customer ID or PO number |
| `total_amount` | `NUMERIC(19,4)` | — | Total monetary value |
| `currency` | `VARCHAR(3)` | `NOT NULL DEFAULT 'USD'` | ISO 4217 uppercase currency code |
| `external_order_reference` | `VARCHAR(255)` | — | External system or ERP reference identifier |
| `actor_user_id` | `UUID` | — | User ID that created the order (nullable) |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Order creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Last modification timestamp |

**Check Constraints:**
- `chk_orders_status`: `CHECK (status IN ('PENDING', 'CONFIRMED', 'FULFILLING', 'FULFILLED', 'CANCELLED', 'EXPIRED'))`
- `chk_orders_currency`: `CHECK (length(currency) = 3 AND currency = upper(currency))`
- `chk_orders_total_amount`: `CHECK (total_amount IS NULL OR total_amount >= 0)`

**Unique constraints:**
- `(organization_id, order_number)`
- `(organization_id, id)` (target for composite foreign keys referencing orders)

**Composite FK:**
- `(organization_id, actor_user_id)` → `organization_memberships(organization_id, user_id)` (nullable FK)

**Supporting Indexes:**
- `idx_orders_org_actor`: `(organization_id, actor_user_id)` (supports actor membership foreign key and order creator filtering)
- `idx_orders_org_status_date`: `(organization_id, status, created_at DESC)` (supports order dashboard filtering)

---

### 10. `order_items`
**Purpose:** Line items within an order. Captures historical point-in-time snapshots of SKU, product name, and unit price.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique line item identifier |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `order_id` | `UUID` | `NOT NULL` | Parent order |
| `line_number` | `INTEGER` | `NOT NULL, CHECK (line_number > 0)` | Monotonic 1-based line sequence within order |
| `product_id` | `UUID` | `NOT NULL` | Product ordered |
| `sku_snapshot` | `VARCHAR(100)` | `NOT NULL` | Point-in-time normalized SKU snapshot |
| `name_snapshot` | `VARCHAR(255)` | `NOT NULL` | Point-in-time product name snapshot |
| `quantity` | `BIGINT` | `NOT NULL, CHECK (quantity > 0)` | Ordered quantity |
| `unit_price` | `NUMERIC(19,4)` | `NOT NULL, CHECK (unit_price >= 0)` | Point-in-time unit price snapshot |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Line creation timestamp |

**Check Constraints:**
- `chk_order_items_line_number`: `CHECK (line_number > 0)`
- `chk_order_items_quantity`: `CHECK (quantity > 0)`
- `chk_order_items_unit_price`: `CHECK (unit_price >= 0)`

**Unique constraints:**
- `(organization_id, id)` (target for composite foreign keys referencing order_items)
- `(organization_id, order_id, line_number)` (prevents duplicate line numbers within order)
- `(organization_id, order_id, id, product_id)` (target for composite foreign key from reservation_allocations)

**Composite FKs:**
- `(organization_id, order_id)` → `orders(organization_id, id)`
- `(organization_id, product_id)` → `products(organization_id, id)`

**Supporting Indexes:**
- `idx_order_items_org_product`: `(organization_id, product_id)` (supports composite foreign key to products and catalog demand lookups)

---

### 11. `reservations`
**Purpose:** Tracks inventory holds committed to an order. Operates on an independent 4-stage lifecycle.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique reservation identifier |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `order_id` | `UUID` | `NOT NULL` | Associated order |
| `status` | `VARCHAR(50)` | `NOT NULL` | Lifecycle: `ACTIVE`, `CONSUMED`, `RELEASED`, `EXPIRED` |
| `expires_at` | `TIMESTAMPTZ` | — | Expiration timestamp for unconfirmed reservations |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Reservation creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Last modification timestamp |

**Check Constraints:**
- `chk_reservations_status`: `CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED', 'EXPIRED'))`

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
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Allocation timestamp |

**Check Constraints:**
- `chk_reservation_allocations_quantity`: `CHECK (quantity > 0)`

**Unique constraints:**
- `(organization_id, reservation_id, order_item_id, warehouse_id)` (prevents duplicate allocations for same line and warehouse)

**Composite FKs:**
- `(organization_id, reservation_id, order_id)` → `reservations(organization_id, id, order_id)`
- `(organization_id, order_id, order_item_id, product_id)` → `order_items(organization_id, order_id, id, product_id)`
- `(organization_id, warehouse_id)` → `warehouses(organization_id, id)`
- `(organization_id, product_id)` → `products(organization_id, id)`

**Supporting Indexes:**
- `idx_allocations_org_reservation`: `(organization_id, reservation_id, order_id)` (supports composite foreign key to reservations and reservation allocation lookups)
- `idx_allocations_org_order_item`: `(organization_id, order_id, order_item_id, product_id)` (supports composite foreign key to order_items and order item allocation lookups)
- `idx_allocations_wh_prod`: `(organization_id, warehouse_id, product_id)` (supports warehouse foreign key and warehouse allocation queries)

**Relational Integrity & Historical Allocation Preservation:**
- **Organization, Order, and Product Integrity:** The composite foreign keys enforce complete relational consistency across tenant, order, reservation, and catalog boundaries at the PostgreSQL schema layer:
  - `(organization_id, reservation_id, order_id) REFERENCES reservations(organization_id, id, order_id)` guarantees that an allocation belongs to the exact same organization and parent order as the referenced reservation.
  - `(organization_id, order_id, order_item_id, product_id) REFERENCES order_items(organization_id, order_id, id, product_id)` guarantees that an allocation belongs to the exact same organization and parent order as the referenced order item, and that the allocated `product_id` strictly matches the product requested on that order item.
  - Because `organization_id` and `order_id` are co-located on `reservation_allocations` and participate in both foreign keys, an allocation cannot cross organization or order boundaries; an allocation cannot reference an order item belonging to a different order than its reservation.
  - `(organization_id, warehouse_id) REFERENCES warehouses(organization_id, id)` and `(organization_id, product_id) REFERENCES products(organization_id, id)` preserve warehouse and product tenant integrity.
- **Product Foreign-Key Indexing Policy (Deliberate Trade-off):** The foreign key `(organization_id, product_id) REFERENCES products(organization_id, id)` is intentionally not backed by a dedicated child-side index. Existing composite indexes containing `product_id` only as a later column (e.g. `idx_allocations_wh_prod` or `idx_allocations_org_order_item`) do not provide leading-column coverage for this foreign key and are not described as supporting it. Under the approved data-retention policy, product rows are never physically deleted (`DELETE FROM products`), and the referenced key columns `(organization_id, id)` are never updated. Non-key product attributes may be updated through ordinary catalog operations, including soft-deactivation through `active = FALSE` and modifications to catalog descriptions or metadata; in PostgreSQL, updates to non-referenced columns do not require or trigger child-side foreign-key delete/update verification. The indexing trade-off is specifically based on the prohibition of physical deletion and referenced-key updates, not on complete row immutability. This is a deliberate write-performance trade-off, not complete index coverage. If physical product deletion or updates to referenced key columns `(organization_id, id)` are ever introduced, the corresponding child-side index `(organization_id, product_id)` must be added first.
- **Order Item Quantity Verification:** For each order item, successful reservation creation must transactionally verify that the sum of allocated quantities across all assigned warehouses exactly equals the order item's requested quantity:
  $$\sum \mathtt{reservation\_allocations.quantity} = \mathtt{order\_items.quantity}$$
  Because standard PostgreSQL table-level `CHECK` constraints cannot execute subqueries or evaluate cross-row aggregations, this aggregate invariant is enforced at the transaction level within the reservation domain service logic before committing.
- **Historical Allocation Preservation:** Allocation records remain permanently available after a reservation transitions to `CONSUMED`, `RELEASED`, or `EXPIRED`. Allocation rows are never deleted upon state transition.
- **Hold Semantics:** The parent `reservations.status` is the sole determinant of whether stock is actively held (`status = 'ACTIVE'`). When a reservation transitions to a terminal state (`CONSUMED`, `RELEASED`, or `EXPIRED`), inventory is physically deducted or released from `inventory_balances.reserved`, but allocation rows are preserved to maintain full historical auditability of warehouse commitments.

---

### 13. `transfers`
**Purpose:** Tracks stock movement between warehouses. Modeled as atomic and immediate in MVP, executed within a single database transaction without intermediate lifecycle states (no `status` column).

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique transfer identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `source_warehouse_id` | `UUID` | `NOT NULL` | Origin warehouse |
| `destination_warehouse_id` | `UUID` | `NOT NULL` | Destination warehouse |
| `notes` | `TEXT` | — | Reason for transfer |
| `actor_user_id` | `UUID` | — | User initiating transfer (nullable) |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Transfer creation timestamp |
| `completed_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Completion timestamp |

**Check Constraints:**
- `chk_transfers_distinct_warehouses`: `CHECK (source_warehouse_id != destination_warehouse_id)`

**Unique constraint:** `(organization_id, id)` (target for composite foreign keys referencing transfers)\
**Composite FKs:**
- `(organization_id, source_warehouse_id)` → `warehouses(organization_id, id)`
- `(organization_id, destination_warehouse_id)` → `warehouses(organization_id, id)`
- `(organization_id, actor_user_id)` → `organization_memberships(organization_id, user_id)` (nullable FK)

**Supporting Indexes:**
- `idx_transfers_org_source_wh`: `(organization_id, source_warehouse_id)` (supports source warehouse foreign key)
- `idx_transfers_org_dest_wh`: `(organization_id, destination_warehouse_id)` (supports destination warehouse foreign key)
- `idx_transfers_org_actor`: `(organization_id, actor_user_id)` (supports actor foreign key)
- `idx_transfers_org_created`: `(organization_id, created_at DESC)`
- `idx_transfers_org_completed`: `(organization_id, completed_at DESC)`

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
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Line creation timestamp |

**Check Constraints:**
- `chk_transfer_items_quantity`: `CHECK (quantity > 0)`

**Unique constraints:**
- `(organization_id, transfer_id, product_id)` (prevents duplicate product lines per transfer)
- `(organization_id, id, product_id)` (target for composite foreign key from inventory_ledger_entries)

**Composite FKs:**
- `(organization_id, transfer_id)` → `transfers(organization_id, id)`
- `(organization_id, product_id)` → `products(organization_id, id)`

**Product Foreign-Key Indexing Policy (Deliberate Trade-off):**
The foreign key `(organization_id, product_id) REFERENCES products(organization_id, id)` is intentionally not backed by a dedicated child-side index. Existing composite unique constraints containing `product_id` only as a later column (`uq_transfer_items_org_transfer_product` and `uq_transfer_items_ledger_target`) contain `product_id` only as a suffix and do not provide leading-column coverage for this foreign key; they are not described as supporting it. Under the approved data-retention policy, product rows are never physically deleted (`DELETE FROM products`), and the referenced key columns `(organization_id, id)` are never updated. Non-key product attributes may be updated through ordinary catalog operations, including soft-deactivation through `active = FALSE` and modifications to catalog descriptions or metadata; in PostgreSQL, updates to non-referenced columns do not require or trigger child-side foreign-key delete/update verification. The indexing trade-off is specifically based on the prohibition of physical deletion and referenced-key updates, not on complete row immutability. This is a deliberate write-performance trade-off, not complete index coverage. If physical product deletion or updates to referenced key columns `(organization_id, id)` are ever introduced, the corresponding child-side index `(organization_id, product_id)` must be added first.

---

### 15. `idempotency_records`
**Purpose:** Prevents duplicate mutation operations across distributed clients and retries.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique record identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `principal_type` | `VARCHAR(50)` | `NOT NULL` | Principal type (e.g. `'USER'`) |
| `principal_id` | `UUID` | `NOT NULL` | Authenticated user or principal ID |
| `operation_scope` | `VARCHAR(100)` | `NOT NULL` | Endpoint or action scope (e.g. `POST:/api/reservations`) |
| `idempotency_key` | `VARCHAR(255)` | `NOT NULL` | Client-provided uniqueness key |
| `request_hash` | `VARCHAR(64)` | `NOT NULL` | SHA-256 hash of all semantically relevant inputs (method, path, body, query parameters, relevant headers) |
| `response_status` | `INTEGER` | — | Committed HTTP response status code |
| `response_body` | `JSONB` | — | Committed JSON response body payload |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Timestamp of processing start |
| `expires_at` | `TIMESTAMPTZ` | `NOT NULL` | Expiration cutoff (default: 7 days) |

**Check Constraints:**
- `chk_idempotency_records_response_pair`: `CHECK ((response_status IS NULL AND response_body IS NULL) OR (response_status IS NOT NULL AND response_body IS NOT NULL))`
- `chk_idempotency_records_status_code`: `CHECK (response_status IS NULL OR (response_status >= 100 AND response_status <= 599))`

**Unique constraint:** `(organization_id, principal_type, principal_id, operation_scope, idempotency_key)`

**Supporting Indexes:**
- `idx_idempotency_cleanup`: `(expires_at)` (supports background worker expiration pruning)

**Concurrency & Lifecycle Semantics:**
- **Initial Insert & Ownership Establishment:** Mutating transactions begin with `INSERT INTO idempotency_records (...) VALUES (...) ON CONFLICT (...) DO NOTHING RETURNING id;`. This statement produces a result set and must be consumed via a query/`ResultSet` API rather than an update count.
- **Concurrent Unique-Index Conflict Waiting:** When Request 2 executes the initial insert while Request 1 is actively executing with the same scoped key, Request 2 encounters a unique index conflict on `(organization_id, principal_type, principal_id, operation_scope, idempotency_key)` and **waits** on the index conflict until Request 1's transaction terminates.
- **Reading/Locking Committed Record After Commit:** If Request 1 successfully commits, Request 2's insert produces 0 rows (`DO NOTHING` handles conflict). Request 2 unblocks, reads and locks the committed record with `SELECT ... FOR UPDATE`, and compares `request_hash`:
  - If `request_hash` matches and `response_status IS NOT NULL`: Request 2 replays the cached response immediately and rolls back without duplicate execution.
  - If `request_hash` differs: Request 2 immediately returns HTTP `409 Conflict` and rolls back.
- **Becoming the Key Owner Through Waiting Insert Upon Rollback:** If Request 1 encounters an error or timeout and rolls back, PostgreSQL's MVCC transaction rollback eliminates Request 1's speculative row and releases its unique index lock. Request 2's waiting insert immediately unblocks and **successfully inserts its own row**, returning 1 row (`RETURNING id` yields its ID). Request 2 becomes the primary key owner directly through its waiting insert without needing a second insert statement, and proceeds to execute business logic.
- **Transactional Consistency & Tenant-Scoped Update:** Idempotency record creation, business operations, and response persistence occur atomically in the same database transaction. The response persistence statement updates `WHERE id = :idemp_id AND organization_id = :org_id`.
- **Client Disconnect Post-Commit Safety:** If a client connection drops after commit, a retry with the same key and payload replays the committed response without re-executing the business operation.
- **Retention & Cleanup Requirement:** 7 days initial design policy. Due to the unique constraint on `(organization_id, principal_type, principal_id, operation_scope, idempotency_key)`, expired records must be pruned/cleaned up by an asynchronous background worker before a unique key can be safely reused.

---

### 16. `audit_logs`
**Purpose:** Append-only record of administrative, system, and user actions.

| Column | Type | Constraints | Description |
|---|---|---|---|
| `id` | `UUID` | `PK` | Unique audit log identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `actor_user_id` | `UUID` | — | User who performed action (nullable for system workers) |
| `action` | `VARCHAR(100)` | `NOT NULL` | Action code (e.g., `RECEIVE_STOCK`, `CONFIRM_ORDER`) |
| `entity_type` | `VARCHAR(50)` | `NOT NULL` | Domain model name (e.g., `order`, `product`) |
| `entity_id` | `UUID` | — | Primary key of affected entity |
| `before_state` | `JSONB` | — | Pre-mutation entity snapshot |
| `after_state` | `JSONB` | — | Post-mutation entity snapshot |
| `metadata` | `JSONB` | — | Contextual execution metadata (IP, user agent, correlation ID) |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Immutable creation timestamp |

**Composite FK:**
- `(organization_id, actor_user_id)` → `organization_memberships(organization_id, user_id)` (nullable FK)

**Supporting Indexes:**
- `idx_audit_logs_actor`: `(organization_id, actor_user_id)` (supports actor membership foreign key and user activity audit trail)
- `idx_audit_logs_org_date`: `(organization_id, created_at DESC)` (supports compliance audit log pagination)
- `idx_audit_logs_entity`: `(organization_id, entity_type, entity_id)` (supports entity-specific audit history lookup)

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
