# STOCKGUARD — Requirements and Scope

> [!IMPORTANT]
> All requirements listed here represent the preliminary design scope for STOCKGUARD. Nothing described in this document has been implemented or tested.

---

## MVP Scope — Included

### Inventory Domain

| Requirement | Description | Status |
|---|---|---|
| Product catalog with normalized SKU | Single `products` table where each product has an organization-unique normalized SKU. Product variants are deferred to future extensions. | **Accepted** |
| Stock receiving and adjustments | Stock receiving into warehouses and manual stock adjustments referencing `product_id`. | **Accepted** |
| Opening balance tracking | Initial stock for a product at a warehouse recorded as an explicit `OPENING_BALANCE` ledger transaction. | **Accepted** |
| Real-time inventory balances | Per-product, per-warehouse records of `on_hand` and `reserved` (`available = on_hand - reserved`). | **Accepted** |
| Append-only inventory ledger | `inventory_ledger_entries` grouped by `inventory_transactions`, capturing `on_hand_delta` and `reserved_delta`. | **Accepted** |
| Mathematical reconciliation | Automated verification that $\sum \text{on\_hand\_delta} = \text{on\_hand}$ and $\sum \text{reserved\_delta} = \text{reserved}$. | **Accepted** |

### Orders Domain

| Requirement | Description | Status |
|---|---|---|
| Orders and order items | Commercial orders containing line items with historical product/SKU/name/price snapshots. | **Accepted** |
| Order lifecycle | `PENDING → CONFIRMED → FULFILLING → FULFILLED`, with terminal cancellation (`CANCELLED`) and expiration (`EXPIRED`). | **Accepted** |
| Reservations and allocations | Distinct lifecycle linking orders to `reservation_allocations` across warehouses. | **Accepted** |
| Reservation lifecycle enforcement | Reservation states: `ACTIVE`, `CONSUMED`, `RELEASED`, `EXPIRED`. (Do not use order states for reservations). | **Accepted** |
| Expiration semantics | A pending order's active reservation may expire past TTL; a confirmed order's active reservation does not automatically expire. | **Accepted** |
| Order fulfillment | Deducts both `on_hand` and `reserved` atomically; transitions order to `FULFILLED` and reservation to `CONSUMED`. | **Accepted** |
| Order cancellation | Releases reservation (`ACTIVE → RELEASED`), decreasing `reserved` while leaving `on_hand` untouched. | **Accepted** |
| Single reservation per order | Only one active reservation permitted per order in MVP. | **Accepted** |
| No partial order reservation | All order line items must be fully reservable, or the entire reservation fails (all-or-nothing). | **Accepted** |

### Warehousing Domain

| Requirement | Description | Status |
|---|---|---|
| Multiple warehouse management | Multiple storage facilities per organization with assigned priority and warehouse code. | **Accepted** |
| Deterministic allocation policy | Evaluates complete line fulfillment preference, then splits across eligible warehouses in warehouse-priority rank order, using warehouse code tie-breaking. | **Accepted** |
| Canonical database lock ordering | Strict lock order (`product_id ASC, warehouse_id ASC` in UUID order) used regardless of business allocation sequence. | **Accepted** |
| Warehouse transfers | Atomic stock movement between warehouses recording transfer items and dual ledger entries; reserved stock cannot be transferred. | **Accepted** |

### Security Domain

| Requirement | Description | Status |
|---|---|---|
| Organization-aware schema | Single-organization launch with organization-aware schema design (`organization_id` on all tables, composite FKs) to preserve boundaries. | **Accepted** |
| User identity & memberships | System `users` mapped to organizations via `organization_memberships` carrying a direct `role` column (`ADMIN`, `MANAGER`, `OPERATOR`, `VIEWER`). | **Accepted** |
| Membership roles table | Separate join table `membership_roles` for dynamic custom roles is deferred post-MVP. | **Deferred** |
| Machine-to-machine API access | Dedicated credentials table `api_clients` deferred unless automated machine API access is a day-one launch requirement. | **Deferred** |

### Operations Domain

| Requirement | Description | Status |
|---|---|---|
| Operations dashboard | Web dashboard for monitoring stock levels, alerts, and movements. | **Accepted** |
| Append-only audit logging | Immutable `audit_logs` tracking user and system mutations. | **Accepted** |
| PostgreSQL-backed idempotency | Scoped by `(organization_id, principal_id, operation_scope, idempotency_key)` with 7-day retention, payload hash verification, concurrent wait-and-replay, and rollback retry. | **Accepted** |
| Runtime configuration store | In-database `system_settings` table deferred to post-MVP; MVP utilizes Spring Boot `application.yml` and environment variables. | **Deferred** |

---

## MVP Scope — Explicitly Excluded

The following are **deliberately out of scope** for the MVP:

| Exclusion | Rationale |
|---|---|
| Full multi-tenant SaaS platform | Self-service tenant provisioning, tenant billing, custom domains, and SaaS administration are out of scope; system uses organization-aware schema starting with one organization |
| Dynamic role configuration (`membership_roles`) | MVP uses direct `role` column on `organization_memberships` (`ADMIN`, `MANAGER`, `OPERATOR`, `VIEWER`); dynamic RBAC tables deferred |
| Database runtime configuration (`system_settings`) | Spring Boot `application.yml` and environment variables handle operational settings in MVP |
| Machine client credentials (`api_clients`) | Deferred unless automated third-party machine integrations are a launch requirement; human interactive dashboard users only in initial MVP |
| Product variants & attributes table | Single `products` table with normalized `sku` chosen for MVP simplicity; separate variant entity deferred to post-MVP |
| Microservices architecture | Modular monolith chosen for development velocity, transactional consistency, and lower operational overhead |
| Kafka / Redpanda | No distributed event streaming infrastructure needed in MVP |
| Redis as inventory authority | PostgreSQL is the single authoritative source of truth for inventory balances |
| Accounting / payroll | Focus is inventory integrity and order reservation |
| Procurement | Purchasing and vendor order management deferred to future releases |
| AI-based demand forecasting | Advanced analytical forecasting out of scope for core consistency MVP |
| Mobile applications | Web operations dashboard only in MVP |
| Multi-region architecture | Single-region database deployment for strict ACID consistency |
| In-transit inventory tracking | Transfers are modeled as instantaneous atomic transactions in MVP |
| Advanced partial fulfillment | Orders cannot be partially reserved or partially fulfilled in MVP |
| Complex shipping optimization | Distance, carrier cost, and dimension-based routing out of scope |

---

## Non-Functional Requirements

| Requirement | Description | Status |
|---|---|---|
| Concurrency safety | Explicit row-level pessimistic locking (`SELECT ... FOR UPDATE`) under `READ COMMITTED` isolation | **Accepted** |
| Canonical lock order | Balances locked strictly by `product_id ASC, warehouse_id ASC` to eliminate deadlocks | **Accepted** |
| Transactional consistency | Balance mutations and ledger entries commit atomically in the same database transaction | **Accepted** |
| Auditability | Append-only ledger and audit log; immutability enforced | **Accepted** |
| Idempotency | All mutating API operations require `Idempotency-Key` with 7-day retention and payload hash validation | **Accepted** |
| Performance testing readiness | Automated test harnesses utilizing Testcontainers and k6 load scenarios | **Accepted** |

---

## Open Requirements & Design Questions

> [!WARNING]
> The following architectural and requirements questions are genuinely **unresolved** and must not be pre-empted without explicit direction:

| # | Question | Notes | Status |
|---|---|---|---|
| 1 | **Authentication token/session strategy** | Stateless JWT bearer tokens vs. stateful session cookies vs. OAuth2 provider. | **Unresolved** |
| 2 | **Exact role-permission matrix** | Explicit permission mappings for roles (`ADMIN`, `MANAGER`, `OPERATOR`, `VIEWER`). | **Unresolved** |
| 3 | **Reservation duration before confirmation** | Default TTL duration for unconfirmed active reservations on pending orders (e.g., 15 min vs. 30 min vs. configurable). | **Unresolved** |
| 4 | **Deadlock retry and backoff configuration** | Exact retry limits, jitter formula, and exponential backoff parameters for PostgreSQL deadlock recovery. | **Unresolved** |
| 5 | **Negative stock adjustment behavior** | Whether an adjustment driving `on_hand < reserved` is rejected with an error or permitted with an audit alert. | **Unresolved** |
| 6 | **Inter-module event strategy** | In-memory Spring `ApplicationEventPublisher` vs. transactional outbox pattern vs. synchronous domain service calls. | **Unresolved** |
| 7 | **Reconciliation schedule and scope** | Scheduled nightly cron vs. on-demand batch runs vs. continuous rolling checks. | **Unresolved** |
| 8 | **Operations dashboard metrics specification** | Exact widget layouts, KPI definitions, and aggregation intervals for the frontend dashboard. | **Unresolved** |
