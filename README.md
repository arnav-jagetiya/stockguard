# STOCKGUARD

> **Architecture and design phase — implementation not yet started.**

## What is STOCKGUARD?

A high-concurrency inventory integrity and order reservation platform for retailers and distributors. The primary engineering objective is to demonstrate strong backend engineering through transactional consistency, concurrent inventory operations, database integrity, idempotency, auditability, testing, and performance verification.

## Problem Statement

Retailers and distributors managing inventory across multiple warehouses face:

- Concurrent operations causing data inconsistency
- Lack of audit trails for inventory movements
- Reservation conflicts during high-demand periods
- Difficulty maintaining accurate real-time inventory counts
- No idempotency guarantees for API operations

STOCKGUARD addresses these challenges with a platform built on transactional integrity from the ground up.

## Core Capabilities (Planned)

- Organization-aware schema architecture (starts with a single organization; composite keys enforce boundaries, while full multi-tenant SaaS features like self-service provisioning and billing are deferred post-MVP)
- Product catalog management with normalized organization-unique SKUs (variant management deferred post-MVP)
- Multi-warehouse inventory tracking with configured priority ranks and warehouse codes
- Stock receiving (including initial opening balance transactions) and adjustments
- Real-time inventory balances (stored `on_hand` and `reserved`; derived `available = on_hand − reserved`; `available` is never independently mutable)
- Append-only dual-delta inventory ledger (`on_hand_delta`, `reserved_delta`) grouped by transactions
- Mathematical inventory reconciliation ($\sum \text{deltas} = \text{balance}$)
- Independent order (`PENDING`, `CONFIRMED`, `FULFILLING`, `FULFILLED`, `CANCELLED`, `EXPIRED`) and reservation (`ACTIVE`, `CONSUMED`, `RELEASED`, `EXPIRED`) lifecycles
- Canonical database lock ordering (`product_id ASC, warehouse_id ASC`) decoupled from deterministic multi-warehouse allocation policy (complete line fulfillment preference, warehouse priority rank, warehouse code tie-breaker; all-or-nothing)
- Automatic reservation expiration via background worker (`FOR UPDATE SKIP LOCKED`)
- Order fulfillment with atomic physical and reserved inventory deduction
- PostgreSQL-backed idempotency scoped by `(organization_id, principal_id, operation_scope, idempotency_key)` with 7-day retention, payload hash conflict detection, transactional consistency, and concurrent duplicate wait-and-replay serialization
- Operations dashboard for real-time inventory visibility and alert management
- Audit logging for all administrative and operational state changes

## Technology Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot, Maven |
| Persistence | Spring Data JPA, Spring JDBC |
| Database | PostgreSQL, Flyway migrations |
| Frontend | React, TypeScript, Vite |
| Testing | JUnit 5, Testcontainers, k6 |
| Infrastructure | Docker |

## Architecture

Modular monolith with clear domain boundaries. PostgreSQL is the authoritative source of truth for all inventory state. The backend uses pessimistic row-level locking for concurrent inventory operations and an append-only ledger for auditability. API-first backend, React operations dashboard.

```
┌─────────────────────┐     ┌──────────────────────────────────────┐
│  React Dashboard    │────▶│         Spring Boot Backend           │
│  (TypeScript/Vite)  │     │                                      │
└─────────────────────┘     │  ┌──────────┐ ┌───────────────────┐  │
                            │  │Inventory │ │  Orders &         │  │
                            │  │& Ledger  │ │  Reservations     │  │
                            │  └──────────┘ └───────────────────┘  │
                            │  ┌──────────┐ ┌───────────────────┐  │
                            │  │Warehouse │ │  Auth & Audit     │  │
                            │  │& Transfer│ │                   │  │
                            │  └──────────┘ └───────────────────┘  │
                            └──────────────┬───────────────────────┘
                                           │
                                    ┌──────▼──────┐
                                    │ PostgreSQL  │
                                    │ (Source of  │
                                    │   Truth)    │
                                    └─────────────┘
```

## Development Status

Current phase: **Architecture and Design**

| Phase | Status |
|---|---|
| Architecture decisions | ✅ Documented |
| Domain model and invariants | ✅ Documented |
| Database design | ✅ Documented |
| API design | 🔲 In progress |
| Backend implementation | 🔲 Not started |
| Frontend implementation | 🔲 Not started |
| Testing | 🔲 Not started |
| Performance verification | 🔲 Not started |

## Repository Structure

```
stockguard/
├── docs/
│   ├── project-bible/          # Project overview, requirements, domain model, principles
│   ├── architecture/           # System architecture and ADRs
│   │   └── adr/                # Architecture Decision Records
│   ├── database/               # Database design, data dictionary, transaction design
│   └── api/                    # API design documentation
├── README.md
└── .gitignore
```

## Planned Implementation Roadmap

1. **Phase 1 — Foundation**: Project scaffolding, database migrations, core domain entities
2. **Phase 2 — Inventory Core**: Inventory balances, ledger, stock receiving, adjustments
3. **Phase 3 — Orders and Reservations**: Order management, reservation lifecycle, allocation
4. **Phase 4 — Advanced Operations**: Fulfillment, transfers, reservation expiration worker
5. **Phase 5 — Dashboard and API**: Operations dashboard, API completion, idempotency
6. **Phase 6 — Testing and Performance**: Comprehensive test suite, load testing with k6
7. **Phase 7 — Polish**: Error handling, logging, reconciliation, documentation updates

## Key Architectural Decisions

- [ADR-001: Modular Monolith](docs/architecture/adr/ADR-001-modular-monolith.md) (over microservices)
- [ADR-002: PostgreSQL as Source of Truth](docs/architecture/adr/ADR-002-postgresql-source-of-truth.md)
- [ADR-003: Pessimistic Row-Level Locking for Inventory Concurrency](docs/architecture/adr/ADR-003-inventory-concurrency.md)
- [ADR-004: Append-Only Inventory Ledger](docs/architecture/adr/ADR-004-inventory-ledger.md)
- [ADR-005: Separate Order and Reservation Lifecycles](docs/architecture/adr/ADR-005-order-reservation-lifecycle.md)
- [ADR-006: Multi-Warehouse Deterministic Allocation](docs/architecture/adr/ADR-006-multi-warehouse-allocation.md)
- [ADR-007: PostgreSQL-Backed Idempotency](docs/architecture/adr/ADR-007-idempotency.md)
- [ADR-008: Dual Persistence Strategy (JPA + JDBC)](docs/architecture/adr/ADR-008-persistence-strategy.md)
- [ADR-009: Background Worker with FOR UPDATE SKIP LOCKED](docs/architecture/adr/ADR-009-reservation-expiration.md)

## License

TBD

---

*This README reflects the current design-phase state of the project. It will be updated as implementation progresses.*
