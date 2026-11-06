# Database Design

> **Status: READY FOR FINAL REVIEW — PENDING PROJECT OWNER SIGN-OFF** — This document describes the database schema design for STOCKGUARD Milestone 2. It is aligned with the authoritative [DATABASE_SCHEMA_SPECIFICATION.md](DATABASE_SCHEMA_SPECIFICATION.md). No tables, DDL scripts, or Flyway migrations have been created yet.

## Accepted Architectural Decisions

| Decision | Reference | Summary |
|---|---|---|
| PostgreSQL as authoritative data store | ADR-002 | PostgreSQL is the single source of truth for all application state. |
| Single `products` table with normalized SKU | — | Products are modeled in a single catalog table with an organization-unique normalized SKU; no separate variant or SKU entity in MVP. |
| UUID primary keys | — | All entities use `UUID` primary keys for global uniqueness and safe distributed generation. |
| PostgreSQL `BIGINT` for inventory quantities | — | PostgreSQL `BIGINT` is a signed 64-bit integer (`-9,223,372,036,854,775,808` to `+9,223,372,036,854,775,807`). It provides a substantially larger numeric range than 32-bit integers, though arithmetic can still overflow if values exceed its representable range. Non-negative balances (`on_hand >= 0`, `reserved >= 0`, `reserved <= on_hand`) and positive business quantities (`quantity > 0`) are enforced through database `CHECK` constraints. Dual ledger movement columns (`on_hand_delta`, `reserved_delta`) leverage signed values for positive and negative movements. Database CHECK constraints enforce value boundaries on stored columns but do not eliminate arithmetic overflow. |
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
| `email` | `VARCHAR(255)` | `NOT NULL` | Global login email address (original input casing) |
| `normalized_email` | `VARCHAR(255)` | `NOT NULL, UNIQUE` | Canonical lowercase trimmed email |
| `password_hash` | `VARCHAR(255)` | — | Hashed password credential string (nullable pending final resolution of the authentication architecture) |
| `active` | `BOOLEAN` | `NOT NULL DEFAULT TRUE` | Soft-deletion / active status flag |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Row creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Last modification timestamp |

**Check Constraints:**
- `chk_users_normalized_email`: `CHECK (normalized_email = lower(trim(email)) AND length(normalized_email) > 0)`

**Unique constraint / index:**
- `normalized_email` (enforces global case-insensitive uniqueness)

#### Authentication Decision Status & Credential Nullability Policy

The authentication strategy for STOCKGUARD is explicitly **unresolved** (Decision #1: *JWT versus session authentication*, along with external identity providers, OAuth2/OIDC, or local credentials).
* **Do Not Pre-empt Unapproved Decisions**: Local password authentication with specific hashing algorithms (such as Argon2id or bcrypt) has not been formally approved as an MVP requirement. Specifying `password_hash` as `NOT NULL` would prematurely force the architecture into local credential management.
* **Schema Neutrality via Nullability**: `password_hash` is specified as nullable (`VARCHAR(255) NULL`) so the physical database schema accommodates any eventual authentication model without requiring breaking DDL modifications.
* **Implications of the Eventual Authentication Decision**:
  - *External Identity Provider (OAuth2 / OIDC / SSO)*: If authentication delegates to an external provider (e.g., corporate SSO, Google, GitHub, Okta), user identities are federated; `password_hash` remains permanently `NULL` and unused.
  - *Local Username/Password Authentication*: If local password authentication is formally ratified, password hashing algorithms (e.g., Argon2id or bcrypt), salt policies, and application validation constraints (ensuring active accounts have non-empty hashes) will be adopted without altering existing relational structures.
  - *Hybrid Authentication*: If both local passwords and federated SSO are supported, `password_hash` will be populated for password-authenticated users and remain `NULL` for federated SSO users.

---

### 3. `organization_memberships`

Associates users with organizations. In the MVP, role-based authorization is captured directly via the `role` column.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique membership identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Associated organization |
| `user_id` | `UUID` | `FK → users(id), NOT NULL` | Associated user identity |
| `role` | `VARCHAR(50)` | `NOT NULL` | MVP role: `ADMIN`, `MANAGER`, `OPERATOR`, `VIEWER` |
| `active` | `BOOLEAN` | `NOT NULL DEFAULT TRUE` | Whether this membership is active |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Row creation timestamp |
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

Catalog entity. Each product represents a single stockable item with a normalized SKU unique within its organization. **No separate `skus` table exists in MVP.**

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique product identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `sku` | `VARCHAR(100)` | `NOT NULL` | SKU code (original input casing preserved) |
| `normalized_sku` | `VARCHAR(100)` | `NOT NULL` | Canonical uppercase trimmed SKU |
| `name` | `VARCHAR(255)` | `NOT NULL` | Product display name |
| `description` | `TEXT` | — | Optional product description |
| `active` | `BOOLEAN` | `NOT NULL DEFAULT TRUE` | Active status flag |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Row creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Last modification timestamp |

**Check Constraints:**
- `chk_products_sku_normalized`: `CHECK (normalized_sku = upper(trim(sku)) AND length(normalized_sku) > 0)`
- `chk_products_name_not_empty`: `CHECK (length(trim(name)) > 0)`

**Unique constraints:**
- `uq_products_org_normalized_sku`: `(organization_id, normalized_sku)`
- `uq_products_org_id`: `(organization_id, id)` (target for composite foreign keys referencing products)

**Supporting Indexes:**
- `idx_products_org_name`: `(organization_id, name)` (supports product catalog search by name)

#### SKU Normalization & Uniqueness Policy

To prevent inconsistent application-side handling and protect data integrity:
1. **Preservation of Original Input:** The original input representation is preserved in `sku`.
2. **Canonical Stored Representation:** The trimmed, uppercase canonical form is stored in `normalized_sku` (`normalized_sku = upper(trim(sku))`).
3. **Treatment of Equivalent Values:** Variations such as `" widget-100 "`, `"Widget-100"`, `"WIDGET-100 "`, and `"widget-100"` resolve to canonical `"WIDGET-100"`.
4. **Organization-Scoped Uniqueness:** Uniqueness is scoped to the organization (`UNIQUE (organization_id, normalized_sku)`).
5. **Database-Level Enforcement:** Guaranteed at the schema layer via check constraint `chk_products_sku_normalized` and unique constraint `uq_products_org_normalized_sku` on `(organization_id, normalized_sku)`.

---

### 5. `warehouses`

Physical storage facilities. Configured with allocation priority rank and normalized warehouse code.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique warehouse identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `code` | `VARCHAR(50)` | `NOT NULL` | Warehouse code (original input preserved) |
| `normalized_code` | `VARCHAR(50)` | `NOT NULL` | Canonical uppercase trimmed code; tie-breaker |
| `name` | `VARCHAR(255)` | `NOT NULL` | Warehouse display name |
| `location` | `VARCHAR(255)` | — | Physical address or region description |
| `priority` | `INTEGER` | `NOT NULL` | Allocation priority rank (lower integer = higher priority) |
| `active` | `BOOLEAN` | `NOT NULL DEFAULT TRUE` | Whether the warehouse is active |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Row creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Last modification timestamp |

**Check Constraints:**
- `chk_warehouses_code_normalized`: `CHECK (normalized_code = upper(trim(code)) AND length(normalized_code) > 0)`
- `chk_warehouses_name_not_empty`: `CHECK (length(trim(name)) > 0)`

**Unique constraints:**
- `uq_warehouses_org_normalized_code`: `(organization_id, normalized_code)`
- `uq_warehouses_org_id`: `(organization_id, id)` (target for composite foreign keys referencing warehouses)

#### Warehouse Code Normalization & Uniqueness Policy

To ensure deterministic warehouse identification and tie-breaking:
1. **Preservation of Original Input:** The original input representation is preserved in `code`.
2. **Canonical Stored Representation:** The trimmed, uppercase canonical form is stored in `normalized_code` (`normalized_code = upper(trim(code))`).
3. **Rejection of Empty Values:** Empty strings or whitespace-only values are strictly rejected (`length(normalized_code) > 0`).
4. **Organization-Scoped Uniqueness:** Warehouse codes are unique within an organization (`UNIQUE (organization_id, normalized_code)`).
5. **Database-Level Enforcement:** Guaranteed at the schema layer via check constraint `chk_warehouses_code_normalized` and unique constraint `uq_warehouses_org_normalized_code` on `(organization_id, normalized_code)`.

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

**Unique constraint:** `(organization_id, product_id, warehouse_id)`\
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
- **Negative Stock Adjustment Decision (Approved):** Downward adjustments to `on_hand` that would cause `on_hand < reserved` (which would implicitly drive `available < 0`) are strictly rejected. Every stock adjustment transaction acquires a row lock (`SELECT ... FOR UPDATE`) on the target balance row, validates $\mathtt{on\_hand + \Delta_{on\_hand} \ge reserved}$, and immediately rolls back without writing balance mutations or ledger entries if the invariant is violated.

---

### 7. `inventory_transactions`

Logical transaction header grouping atomic inventory ledger entries (e.g., receiving batches, reservations, fulfillments, transfers).

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique transaction identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `transaction_type` | `VARCHAR(50)` | `NOT NULL` | `OPENING_BALANCE`, `RECEIPT`, `ADJUSTMENT`, `RESERVATION`, `RELEASE`, `FULFILLMENT`, `TRANSFER` |
| `reference_type` | `VARCHAR(50)` | — | Originating entity category (`ORDER`, `TRANSFER`, `MANUAL`; NULL for standalone receipts/opening balances) |
| `reference_id` | `UUID` | — | Primary key of the originating entity (`orders.id`, `transfers.id`, or external supporting document UUID such as a stock-count sheet or discrepancy ticket; NULL when reference_type is NULL) |
| `notes` | `TEXT` | — | Audit description or operator explanation |
| `actor_user_id` | `UUID` | — | User ID that triggered the operation (nullable) |
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

#### Reference Semantics & Manual Adjustment Target
- `reference_type` categorizes the originating business entity:
  - `'ORDER'`: Originates from commercial order operations (reservations, fulfillments, cancellations, expirations); `reference_id` references `orders.id`.
  - `'TRANSFER'`: Originates from stock relocation; `reference_id` references `transfers.id`.
  - `'MANUAL'`: Originates from manual inventory adjustments (`POST /api/inventory/adjust`) supported by an external document. In this case, `reference_id` stores the UUID of that external supporting document (such as a physical stock-count sheet or discrepancy ticket). Note that `reference_id` does not declare a database foreign key, as external documents reside outside the database schema. If no external supporting document exists, both `reference_type` and `reference_id` are stored as `NULL`, with operator attribution captured in `actor_user_id` and the business justification recorded in `notes`. Audit records remain independently recorded in `audit_logs`.
- Standalone stock receipts (`RECEIPT`) and opening balances (`OPENING_BALANCE`) have no external purchasing entity table in the MVP and store `(NULL, NULL)` for the reference pair.

---

### 8. `inventory_ledger_entries`

Immutable, append-only ledger recording every balance modification with dual deltas.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique ledger entry identifier |
| `transaction_id` | `UUID` | `NOT NULL` | Parent transaction |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `entry_number` | `INTEGER` | `NOT NULL, CHECK (entry_number > 0)` | Monotonic 1-based line sequence within transaction |
| `product_id` | `UUID` | `NOT NULL` | Affected product |
| `warehouse_id` | `UUID` | `NOT NULL` | Affected warehouse |
| `on_hand_delta` | `BIGINT` | `NOT NULL` | Signed change in `on_hand` (+ receipt/transfer-in, - fulfill/transfer-out, 0 reservation) |
| `reserved_delta` | `BIGINT` | `NOT NULL` | Signed change in `reserved` (+ reserve, - release/fulfill/expire, 0 adjustment) |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Immutable entry timestamp |
| `transfer_item_id` | `UUID` | — | Line item ID if originating from warehouse transfer (nullable) |
| `transfer_direction` | `VARCHAR(10)` | — | Transfer direction (`OUT` or `IN`; nullable) |

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

#### Migration Dependency & Creation-Order Clarification

`inventory_ledger_entries` defines a composite foreign key referencing `transfer_items`:
$$\mathtt{(organization\_id, transfer\_item\_id, product\_id) \ REFERENCES \ transfer\_items(organization\_id, id, product\_id)}$$
Because `transfer_items` (Table 14) is logically described after `inventory_ledger_entries` (Table 8) in the schema hierarchy:
* **Flyway Migration Execution Pattern**: In the migration scripts, either:
  1. *Dependency-Ordered Table Creation*: Create `transfers` and `transfer_items` prior to `inventory_ledger_entries`, OR
  2. *(Recommended Pattern)* Create all tables first with primary keys and unique constraints, and then add cross-table forward-referencing foreign keys—specifically `fk_inventory_ledger_entries_transfer_item`—via a subsequent `ALTER TABLE inventory_ledger_entries ADD CONSTRAINT fk_inventory_ledger_entries_transfer_item ...` statement once `transfer_items` exists.
* **Target Unique Constraint Verification**: Verified that `transfer_items` explicitly declares `UNIQUE (organization_id, id, product_id)`, satisfying PostgreSQL composite foreign key target requirements.

#### Transfer Ledger Pairing Invariant & Application Verification Guarantees

Partial unique indexes on `(organization_id, transfer_item_id)` (`WHERE transfer_direction = 'OUT'` and `WHERE transfer_direction = 'IN'`) guarantee at the database level that **at most one** `OUT` and **at most one** `IN` entry can exist per transfer line item. They prevent duplicate directional entries but do not independently guarantee that *both* paired entries exist or that they are balanced.

The application transaction boundary strictly verifies that:
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

---

### 9. `orders`

Commercial order entity tracking customer commitments across their independent lifecycle.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique order identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `order_number` | `VARCHAR(100)` | `NOT NULL` | Human-readable order number |
| `status` | `VARCHAR(50)` | `NOT NULL` | Lifecycle: `PENDING`, `CONFIRMED`, `FULFILLING`, `FULFILLED`, `CANCELLED`, `EXPIRED` |
| `customer_reference`| `VARCHAR(255)` | — | External customer identifier or PO number |
| `total_amount` | `NUMERIC(19,4)` | — | Total monetary value |
| `currency` | `VARCHAR(3)` | `NOT NULL DEFAULT 'USD'` | ISO 4217 uppercase currency code |
| `external_order_reference` | `VARCHAR(255)` | — | External system or ERP reference identifier |
| `actor_user_id` | `UUID` | — | User ID that created the order (nullable) |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Row creation timestamp |
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

Line items for an order. Captures immutable historical snapshots of SKU, product name, and price at time of order creation.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique line item identifier |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `order_id` | `UUID` | `NOT NULL` | Parent order |
| `line_number` | `INTEGER` | `NOT NULL, CHECK (line_number > 0)` | Monotonic 1-based line sequence within order |
| `product_id` | `UUID` | `NOT NULL` | Product ordered |
| `sku_snapshot` | `VARCHAR(100)` | `NOT NULL` | Point-in-time normalized SKU snapshot |
| `name_snapshot` | `VARCHAR(255)` | `NOT NULL` | Point-in-time product name snapshot |
| `quantity` | `BIGINT` | `NOT NULL, CHECK (quantity > 0)` | Ordered quantity |
| `unit_price` | `NUMERIC(19,4)` | `NOT NULL, CHECK (unit_price >= 0)` | Point-in-time price snapshot |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Row creation timestamp |

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
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Row creation timestamp |

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

#### Relational Integrity & Historical Allocation Preservation
- **Organization, Order, and Product Integrity:** The composite foreign keys enforce complete relational consistency across tenant, order, reservation, and catalog boundaries at the PostgreSQL schema layer:
  - `(organization_id, reservation_id, order_id) REFERENCES reservations(organization_id, id, order_id)` guarantees that an allocation belongs to the exact same organization and parent order as the referenced reservation.
  - `(organization_id, order_id, order_item_id, product_id) REFERENCES order_items(organization_id, order_id, id, product_id)` guarantees that an allocation belongs to the exact same organization and parent order as the referenced order item, and that the allocated `product_id` strictly matches the product specified on the order item.
  - Because `organization_id` and `order_id` are co-located on `reservation_allocations` and participate in both foreign keys, an allocation cannot cross organization or order boundaries; an allocation cannot reference an order item belonging to a different order than its reservation.
  - Existing warehouse and product foreign keys `(organization_id, warehouse_id) REFERENCES warehouses(organization_id, id)` and `(organization_id, product_id) REFERENCES products(organization_id, id)` preserve facility and catalog tenant integrity.
- **Product Foreign-Key Indexing Policy (Deliberate Trade-off):** The foreign key `(organization_id, product_id) REFERENCES products(organization_id, id)` is intentionally not backed by a dedicated child-side index. Existing indexes containing `product_id` only as a later column (e.g. `idx_allocations_wh_prod` or `idx_allocations_org_order_item`) do not provide leading-column coverage for this foreign key and are not described as supporting it. Under the approved data-retention policy, product rows are never physically deleted (`DELETE FROM products`), and the referenced key columns `(organization_id, id)` are never updated. Non-key product attributes may be updated through ordinary catalog operations, including soft-deactivation through `active = FALSE` and modifications to catalog descriptions or metadata; in PostgreSQL, updates to non-referenced columns do not require or trigger child-side foreign-key delete/update verification. The indexing trade-off is specifically based on the prohibition of physical deletion and referenced-key updates, not on complete row immutability. This is a deliberate write-performance trade-off to minimize write amplification on a high-throughput line table. If physical product deletion or updates to referenced key columns `(organization_id, id)` are ever introduced, the corresponding child-side index `(organization_id, product_id)` must be added first.
- **Order Item Quantity Verification:** For each order item, successful reservation creation must transactionally verify that the sum of allocated quantities across all assigned warehouses exactly equals the order item's requested quantity:
  $$\sum \mathtt{reservation\_allocations.quantity} = \mathtt{order\_items.quantity}$$
  Because PostgreSQL table-level `CHECK` constraints can only evaluate single-row expressions and cannot evaluate cross-row aggregations, this aggregate invariant is enforced at the transaction level within the reservation service logic.
- **Preserved History:** `reservation_allocations` records remain permanently available after a reservation transitions to `CONSUMED`, `RELEASED`, or `EXPIRED`. Allocation rows are never deleted upon state transition.
- **Hold Control:** The parent `reservations.status` is the sole determinant of whether inventory is actively held (`status = 'ACTIVE'`). When a reservation transitions to a terminal state (`CONSUMED`, `RELEASED`, or `EXPIRED`), inventory is physically deducted or released from `inventory_balances.reserved`, but allocation rows are preserved to maintain a complete historical record of warehouse commitments.

---

### 13. `transfers`

Records stock relocation between two warehouses within the same organization. In MVP, transfers represent completed atomic stock relocations executed immediately within a single transaction; therefore, no intermediate lifecycle `status` column is defined.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique transfer identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `source_warehouse_id` | `UUID` | `NOT NULL` | Origin warehouse |
| `destination_warehouse_id` | `UUID` | `NOT NULL` | Destination warehouse |
| `notes` | `TEXT` | — | Reason or reference for the transfer |
| `actor_user_id` | `UUID` | — | User ID that initiated transfer (nullable) |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Row creation timestamp |
| `completed_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Completion timestamp |

**Check Constraints:**
- `chk_transfers_distinct_warehouses`: `CHECK (source_warehouse_id != destination_warehouse_id)`

**Unique constraint:** `(organization_id, id)` (target for composite foreign keys referencing transfers)

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

Line items for stock transfers.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique transfer line identifier |
| `organization_id` | `UUID` | `NOT NULL` | Owning organization |
| `transfer_id` | `UUID` | `NOT NULL` | Parent transfer |
| `product_id` | `UUID` | `NOT NULL` | Product transferred |
| `quantity` | `BIGINT` | `NOT NULL, CHECK (quantity > 0)` | Quantity transferred |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Row creation timestamp |

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

#### Multi-Item Transfer Concurrency & Global Canonical Lock Ordering
- A transfer supports multiple items (`transfer_items`) between a source and destination warehouse within the same organization.
- **Pre-Creation of Missing Destination Balances:** Before acquiring any row locks, missing destination balance rows for all transfer products are inserted with `on_hand = 0, reserved = 0` via `INSERT ... ON CONFLICT (organization_id, product_id, warehouse_id) DO NOTHING` to guarantee that all rows exist for locking.
- **Global Canonical Lock Order:** To prevent deadlocks, all affected balance rows (both source and destination across all transfer line items) are locked in **one global canonical order**: `product_id ASC, warehouse_id ASC`. Locks are never acquired product-by-product in arbitrary request order.
- **Availability & Ledger Pairing:** The source warehouse must have sufficient available stock ($\mathtt{on\_hand - reserved \ge quantity}$) for every line item prior to deduction; reserved stock cannot be transferred. For every line item, exactly one `OUT` entry and one `IN` entry are appended atomically to `inventory_ledger_entries`.

---

### 15. `idempotency_records`

PostgreSQL-backed store ensuring at-most-once execution of mutating API operations.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique record identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `principal_type` | `VARCHAR(50)` | `NOT NULL` | Actor type (e.g. `'USER'`) |
| `principal_id` | `UUID` | `NOT NULL` | Authenticated user ID |
| `operation_scope` | `VARCHAR(100)` | `NOT NULL` | Endpoint or operation identifier (e.g. `POST:/api/reservations`) |
| `idempotency_key` | `VARCHAR(255)` | `NOT NULL` | Client-provided uniqueness key |
| `request_hash` | `VARCHAR(64)` | `NOT NULL` | SHA-256 hash of all semantically relevant inputs (method, path, body, query parameters, relevant headers) |
| `response_status` | `INTEGER` | — | Committed HTTP response status code |
| `response_body` | `JSONB` | — | Committed JSON response body payload |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Record creation timestamp |
| `expires_at` | `TIMESTAMPTZ` | `NOT NULL` | Expiration cutoff (default: 7 days) |

**Check Constraints:**
- `chk_idempotency_records_response_pair`: `CHECK ((response_status IS NULL AND response_body IS NULL) OR (response_status IS NOT NULL AND response_body IS NOT NULL))`
- `chk_idempotency_records_status_code`: `CHECK (response_status IS NULL OR (response_status >= 100 AND response_status <= 599))`

**Unique constraint:** `(organization_id, principal_type, principal_id, operation_scope, idempotency_key)`

**Supporting Indexes:**
- `idx_idempotency_cleanup`: `(expires_at)` (supports background worker expiration pruning)

#### Idempotency Concurrency & Transactional Semantics

- **Scoping:** Idempotency is strictly scoped by `(organization_id, principal_type, principal_id, operation_scope, idempotency_key)`.
- **Same Key & Same Payload:** If a committed record exists with matching scope (indicated by non-null `response_status` and `response_body`) and identical `request_hash`, the server returns the cached response status and body without re-executing business logic.
- **Same Key & Different Payload:** If a record exists with matching scope but a differing `request_hash`, the server rejects the request with HTTP `409 Conflict`.
- **Initial Insert & Ownership Establishment:** Mutating transactions begin with `INSERT INTO idempotency_records (...) VALUES (...) ON CONFLICT (...) DO NOTHING RETURNING id;`. This statement produces a result set and must be consumed via a query/`ResultSet` API rather than an update count.
- **Concurrent Unique-Index Conflict Waiting:** When Request 2 executes the initial insert while Request 1 is actively executing with the same scoped key, Request 2 encounters a unique index conflict on `(organization_id, principal_type, principal_id, operation_scope, idempotency_key)` and **waits** on the index conflict until Request 1's transaction terminates.
- **Reading/Locking Committed Record After Commit:** If Request 1 successfully commits, Request 2's insert produces 0 rows (`DO NOTHING` handles conflict). Request 2 unblocks, reads and locks the committed record with `SELECT ... FOR UPDATE`, and compares `request_hash`:
  - If `request_hash` matches and `response_status IS NOT NULL`: Request 2 replays the cached response immediately and rolls back without duplicate execution.
  - If `request_hash` differs: Request 2 immediately returns HTTP `409 Conflict` and rolls back.
- **Becoming the Key Owner Through Waiting Insert Upon Rollback:** If Request 1 encounters an error or timeout and rolls back, PostgreSQL's MVCC transaction rollback eliminates Request 1's speculative row and releases its unique index lock. Request 2's waiting insert immediately unblocks and **successfully inserts its own row**, returning 1 row (`RETURNING id` yields its ID). Request 2 becomes the primary key owner directly through its waiting insert without needing a second insert statement, and proceeds to execute business logic.
- **Transactional Consistency & Tenant-Scoped Update:** Idempotency record creation, business operations, and response persistence occur atomically in the same database transaction. The response persistence statement updates `WHERE id = :idemp_id AND organization_id = :org_id`.
- **Client Disconnect Post-Commit Safety:** If a client connection drops or times out after the database transaction has successfully committed, the business mutation has already executed and the response is safely stored in `idempotency_records`. A subsequent retry with the same key and payload does not re-execute the business operation; it detects the committed record and immediately replays the cached response.
- **Proposed Retention & Cleanup Requirement:** Retained for **7 days** as an initial design policy. Due to the unique constraint on `(organization_id, principal_type, principal_id, operation_scope, idempotency_key)`, expired records must be pruned/cleaned up by an asynchronous background job before a unique key can be safely reused.

---

### 16. `audit_logs`

Immutable append-only record of administrative, system, and user actions for compliance and auditability.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique log identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `actor_user_id` | `UUID` | — | Acting user (nullable for background workers) |
| `action` | `VARCHAR(100)` | `NOT NULL` | Action code (e.g., `RECEIVE_STOCK`, `CONFIRM_ORDER`) |
| `entity_type` | `VARCHAR(50)` | `NOT NULL` | Domain entity affected (e.g., `order`, `product`) |
| `entity_id` | `UUID` | — | Primary key of the affected entity |
| `before_state` | `JSONB` | — | Pre-mutation entity snapshot |
| `after_state` | `JSONB` | — | Post-mutation entity snapshot |
| `metadata` | `JSONB` | — | Additional contextual metadata (IP, user agent, correlation ID) |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Immutable creation timestamp |

**Composite FK:**
- `(organization_id, actor_user_id)` → `organization_memberships(organization_id, user_id)` (nullable FK)

**Supporting Indexes:**
- `idx_audit_logs_actor`: `(organization_id, actor_user_id)` (supports actor membership foreign key and user activity audit trail)
- `idx_audit_logs_org_date`: `(organization_id, created_at DESC)` (supports compliance audit log pagination)
- `idx_audit_logs_entity`: `(organization_id, entity_type, entity_id)` (supports entity-specific audit history lookup)

---

## Detailed Table Schemas — Deferred Extensions (3 Tables)

The following tables are documented for future architecture phases and will not be created in the initial MVP Flyway migration.

### 17. `membership_roles` (Deferred)

Join table allowing multiple custom roles and granular permission sets per membership.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique role assignment identifier |
| `membership_id` | `UUID` | `FK → organization_memberships(id), NOT NULL` | Owning membership |
| `role` | `VARCHAR(50)` | `NOT NULL` | Role code |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Row creation timestamp |

**Unique constraint:** `(membership_id, role)`\
**MVP Strategy:** Deferred. MVP utilizes the direct `role` column on `organization_memberships`.

---

### 18. `system_settings` (Deferred)

Dynamic key-value configuration store for runtime operational settings.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique setting identifier |
| `organization_id` | `UUID` | — | Nullable (`NULL` = system-wide default, non-null = organization override) |
| `setting_key` | `VARCHAR(100)` | `NOT NULL` | Configuration key (e.g., `reservation.ttl_seconds`) |
| `setting_value` | `TEXT` | `NOT NULL` | Serialized setting value |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Row creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Last modification timestamp |

**Unique constraint:** `(organization_id, setting_key)`\
**MVP Strategy:** Deferred. MVP utilizes Spring Boot `application.yml` and environment variables.

---

### 19. `api_clients` (Deferred)

Machine-to-machine API credentials for external automated integrations.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | `UUID` | `PRIMARY KEY` | Unique client identifier |
| `organization_id` | `UUID` | `FK → organizations(id), NOT NULL` | Owning organization |
| `name` | `VARCHAR(255)` | `NOT NULL` | Human-readable client label |
| `client_id` | `VARCHAR(100)` | `NOT NULL, UNIQUE` | Public client identifier |
| `client_secret_hash` | `VARCHAR(255)` | `NOT NULL` | Securely hashed client secret |
| `active` | `BOOLEAN` | `NOT NULL DEFAULT TRUE` | Whether the client credential is valid |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Row creation timestamp |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT CURRENT_TIMESTAMP` | Last modification timestamp |

**MVP Strategy:** Deferred unless automated external machine integrations are mandated as a day-one launch requirement.

---

## Proposed Index Strategy

- **Foreign Keys:** Child-side indexes support all single-column and composite foreign keys across the schema, with the intentional exception of two unindexed product foreign keys:
  - `reservation_allocations (organization_id, product_id) REFERENCES products (organization_id, id)`
  - `transfer_items (organization_id, product_id) REFERENCES products (organization_id, id)`
  These two foreign keys are intentionally not backed by dedicated child-side indexes. Existing composite indexes and unique constraints that contain `product_id` only as a later column (such as `idx_allocations_wh_prod`, `idx_allocations_org_order_item`, `uq_transfer_items_org_transfer_product`, and `uq_transfer_items_ledger_target`) do not provide leading-column coverage for these foreign keys and are not described as supporting them. Under the approved data-retention policy, product rows are never physically deleted (`DELETE FROM products`), and the referenced key columns `(organization_id, id)` are never updated. Non-key product attributes may be updated through ordinary catalog operations, including soft-deactivation through `active = FALSE` and modifications to catalog descriptions or metadata; in PostgreSQL, updates to non-referenced columns do not require or trigger child-side foreign-key delete/update verification. The indexing trade-off is specifically based on the prohibition of physical deletion and referenced-key updates, not on complete row immutability. This is a deliberate write-performance trade-off, not complete index coverage. If physical product deletion or updates to referenced key columns `(organization_id, id)` are ever introduced, the corresponding child-side indexes must be added first.
- **Membership User Foreign Key Supporting Index:** `CREATE INDEX idx_memberships_user ON organization_memberships (user_id);` (supports child-side foreign key to `users`).
- **Product Name Search Supporting Index:** `CREATE INDEX idx_products_org_name ON products (organization_id, name);` (supports catalog lookups and search).
- **Normalized SKU Unique Index:** Enforced via table unique constraint `uq_products_org_normalized_sku` on `(organization_id, normalized_sku)`.
- **Normalized Warehouse Code Unique Index:** Enforced via table unique constraint `uq_warehouses_org_normalized_code` on `(organization_id, normalized_code)`.
- **Order Number Unique Index:** `UNIQUE (organization_id, order_number)`.
- **Order Line Number Unique Index:** `UNIQUE (organization_id, order_id, line_number)`.
- **One Reservation Per Order Unique Index:** `UNIQUE (organization_id, order_id)`.
- **Reservation Allocation Parent Unique Indexes:**
  - `reservations(organization_id, id, order_id)` to support composite foreign key `(organization_id, reservation_id, order_id)`.
  - `order_items(organization_id, order_id, id, product_id)` to support composite foreign key `(organization_id, order_id, order_item_id, product_id)`.
- **Reservation Allocation Unique Index:** `UNIQUE (organization_id, reservation_id, order_item_id, warehouse_id)`.
- **Reservation Allocation Child-Side Foreign Key Indexes:**
  - `CREATE INDEX idx_allocations_org_reservation ON reservation_allocations (organization_id, reservation_id, order_id);` (supports composite foreign key to `reservations`)
  - `CREATE INDEX idx_allocations_org_order_item ON reservation_allocations (organization_id, order_id, order_item_id, product_id);` (supports composite foreign key to `order_items`)
  - `CREATE INDEX idx_allocations_wh_prod ON reservation_allocations (organization_id, warehouse_id, product_id);` (supports warehouse foreign key and allocation rollups)
- **Balances Canonical Tenant Unique Index:** `UNIQUE (organization_id, product_id, warehouse_id)`. Within each tenant, the leading key sequence `(product_id ASC, warehouse_id ASC)` directly services canonical lock acquisition (`SELECT ... FOR UPDATE ... ORDER BY product_id ASC, warehouse_id ASC`) without requiring a separate redundant lock index.
- **Balances Warehouse Query Index:** `CREATE INDEX idx_balances_warehouse ON inventory_balances (organization_id, warehouse_id);` (services warehouse-level inventory queries and foreign key lookups).
- **Active Reservation Expiration Partial Index:**
  ```sql
  CREATE INDEX idx_reservations_active_expires
    ON reservations (expires_at)
    WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;
  ```
- **Ledger Sequence Unique Index:** `UNIQUE (organization_id, transaction_id, entry_number)`.
- **Ledger Reconciliation Index:** Composite index `idx_ledger_reconciliation` on `inventory_ledger_entries (organization_id, product_id, warehouse_id, created_at)` to optimize mathematical reconciliation queries and balance verification.
- **Ledger Transfer Partial Unique Indexes:**
  ```sql
  CREATE UNIQUE INDEX idx_ledger_transfer_out
    ON inventory_ledger_entries (organization_id, transfer_item_id)
    WHERE transfer_direction = 'OUT';

  CREATE UNIQUE INDEX idx_ledger_transfer_in
    ON inventory_ledger_entries (organization_id, transfer_item_id)
    WHERE transfer_direction = 'IN';
  ```
- **Ledger Transfer Item Foreign Key Supporting Index:**
  ```sql
  CREATE INDEX idx_ledger_org_transfer_item
    ON inventory_ledger_entries (organization_id, transfer_item_id, product_id)
    WHERE transfer_item_id IS NOT NULL;
  ```
- **Idempotency Lookup Unique Index:** `UNIQUE (organization_id, principal_type, principal_id, operation_scope, idempotency_key)`.
- **Idempotency Expiration Cleanup Index:**
  ```sql
  CREATE INDEX idx_idempotency_cleanup
    ON idempotency_records (expires_at);
  ```
- **Operational Query Indexes:**
  - `inventory_transactions (organization_id, actor_user_id)` (child-side actor FK support and operator transaction history)
  - `inventory_transactions (organization_id, created_at DESC)`
  - `inventory_transactions (organization_id, reference_type, reference_id)`
  - `inventory_ledger_entries (organization_id, warehouse_id)` (facility-level ledger audit queries and warehouse FK support)
  - `orders (organization_id, status, created_at DESC)` (supports order dashboard status filtering and pagination)
  - `orders (organization_id, created_at DESC)` (supports timeline pagination)
  - `orders (organization_id, actor_user_id)` (child-side actor FK support and order creator filtering)
  - `order_items (organization_id, product_id)` (child-side product FK support and catalog demand queries)
  - `transfers (organization_id, source_warehouse_id)` (child-side source warehouse FK support)
  - `transfers (organization_id, destination_warehouse_id)` (child-side destination warehouse FK support)
  - `transfers (organization_id, created_at DESC)`
  - `transfers (organization_id, completed_at DESC)`
  - `transfers (organization_id, actor_user_id)` (child-side actor FK support and transfer initiator filtering)
  - `audit_logs (organization_id, created_at DESC)`
  - `audit_logs (organization_id, entity_type, entity_id)`
  - `audit_logs (organization_id, actor_user_id)` (child-side actor FK support and user audit trail)
