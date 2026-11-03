# ADR-002: PostgreSQL as Source of Truth for Inventory State

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-02 |

> [!IMPORTANT]
> This is an architectural decision record. Nothing described here has been implemented or tested.

## Context

Inventory integrity is a critical requirement for StockGuard. Incorrect balances lead to overselling, stockouts, and operational failures. The system needs a data store that guarantees strong consistency for all inventory state.

Alternative approaches considered:

- **Caching layers (e.g., Redis) as primary authority** — introduces consistency risks between cache and database; cache invalidation is error-prone for financial-grade data
- **Event stores as primary authority** — eventual consistency model is unsuitable for inventory operations where real-time accuracy is required
- **Dual-write to multiple stores** — creates distributed consistency problems without distributed transactions

PostgreSQL provides ACID transactions, row-level locking, mature tooling, and a well-understood operational model.

## Decision

**PostgreSQL is the authoritative source of all inventory state.** Specifically:

- No caching layer (Redis, Memcached) holds authoritative inventory data
- No message broker or event store is the source of truth for balances
- No external system holds inventory state that could conflict with PostgreSQL
- All inventory reads and writes go through PostgreSQL

## Consequences

### Benefits

- **Strong consistency guaranteed** — ACID transactions ensure inventory balances are always accurate
- **Simpler operational model** — one authoritative data store; no cache invalidation bugs, no eventual consistency windows
- **Mature tooling** — backups, replication, monitoring, and recovery are well-understood for PostgreSQL
- **Row-level locking** — supports concurrent operations without application-level locking (see [ADR-003](ADR-003-inventory-concurrency.md))

### Risks and Trade-offs

- **Performance ceiling** — all reads hit the database; high-throughput read-heavy workloads may require optimization
- **Query and index design** — must design queries and indexes carefully for performance at scale
- **No read scaling out of the box** — a single primary handles all reads and writes

### Future Considerations

- Read replicas may be introduced for read-heavy dashboard queries if performance requires it (not in MVP)
- A caching layer (e.g., Redis) could be added for non-authoritative read acceleration (product catalog, dashboard aggregations) — but never as the source of truth for inventory balances
