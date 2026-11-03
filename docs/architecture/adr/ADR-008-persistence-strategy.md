# ADR-008: Dual Persistence Strategy — JPA and JDBC

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-02 (Updated 2026-11-03) |

> [!IMPORTANT]
> This is an architectural decision record. Nothing described here has been implemented or tested.

## Context

Spring Data JPA provides high developer productivity for standard CRUD operations — entity mapping, repository interfaces, query derivation, and pagination. However, critical inventory operations require **explicit SQL control** that JPA abstracts away:

- **Precise locking hints** — `SELECT ... FOR UPDATE`, `FOR UPDATE SKIP LOCKED` are difficult to control reliably across complex joins in JPA.
- **Predictable ordering** — acquiring row-level locks strictly in canonical `product_id ASC, warehouse_id ASC` order requires explicit SQL guarantees.
- **Batch operations** — JPA's entity-by-entity change tracking is inefficient for high-throughput ledger logging.
- **Performance-sensitive queries** — multi-table reconciliation aggregations and atomic balance mutations benefit from hand-tuned SQL.

Using only JPA sacrifices control where it is needed most. Using only JDBC sacrifices productivity where standard CRUD suffices.

## Decision

Use a **dual persistence strategy**:

### JPA (Spring Data JPA) — for ordinary entity persistence

Used for standard domain entity lifecycle management where object mapping and CRUD productivity are paramount:
- **MVP Tables:**
  - `organizations`
  - `users`, `organization_memberships` (with direct `role` column)
  - `products` (single catalog table with normalized SKU)
  - `warehouses`
  - `orders`, `order_items`
  - `transfers`, `transfer_items` (metadata queries)
  - `audit_logs` (general log writes)
- **Deferred Future Extensions (when implemented):**
  - `membership_roles` (deferred; MVP uses role column on memberships)
  - `api_clients` (deferred unless machine API is day-one launch requirement)
  - `system_settings` (deferred; MVP uses application configuration)

### JDBC (Spring JDBC / NamedParameterJdbcTemplate) — for critical SQL operations

Used for high-concurrency, performance-sensitive, and lock-intensive operations:
- `inventory_balances` read-and-lock (`SELECT ... FOR UPDATE` in canonical `product_id ASC, warehouse_id ASC` order) and updates
- `inventory_transactions` and `inventory_ledger_entries` batch appends
- `reservations` and `reservation_allocations` creation and lock acquisition
- Reservation expiration polling (`SELECT ... FOR UPDATE SKIP LOCKED` on expired `ACTIVE` reservations for `PENDING` orders)
- Transfer stock balance movements (simultaneous two-warehouse balance locking)
- Reconciliation queries ($\sum \text{on\_hand\_delta}$ and $\sum \text{reserved\_delta}$)
- `idempotency_records` checks and writes

Both JPA and JDBC participate in the same Spring `PlatformTransactionManager` (`JpaTransactionManager`), ensuring atomic transaction boundaries across both persistence paradigms.

## Consequences

### Benefits

- **Developer productivity:** Rapid development of administrative and CRUD endpoints using Spring Data repositories.
- **Explicit concurrency control:** Hand-written SQL ensures locks are acquired strictly in canonical `product_id ASC, warehouse_id ASC` order without ORM interference.
- **Auditable SQL:** Critical locking queries are visible in the codebase, simplifying code reviews and performance profiling.
- **Shared transaction context:** Both JPA and JDBC operations participate in the same database transaction.

### Risks and Trade-offs

- **Dual paradigm discipline:** Developers must know which persistence layer to use for each feature.
- **L1 cache synchronization:** JDBC updates bypass Hibernate's first-level cache; JPA entities representing mutated balances must not be cached across transaction boundaries.

## Unresolved Questions

| # | Question |
|---|---|
| 1 | Boundary enforcement — automated ArchUnit tests to prevent accidental JPA queries with locking on critical inventory tables. |
| 2 | Query builder approach for dynamic JDBC queries — JOOQ vs. raw SQL templates with `NamedParameterJdbcTemplate`. |
