# ADR-001: Use Modular Monolith Architecture

| Field | Value |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-02 |

> [!IMPORTANT]
> This is an architectural decision record. Nothing described here has been implemented or tested.

## Context

StockGuard needs an architecture that balances development velocity with clean, maintainable structure for a team building an inventory management platform. Microservices introduce significant operational complexity — service mesh, distributed transactions, eventual consistency, independent deployment pipelines, inter-service communication — that is premature for the MVP phase.

The team requires:

- Fast iteration during early development
- Clear domain boundaries to support future extraction
- Transactional consistency across inventory operations
- Manageable operational overhead

## Decision

Build StockGuard as a **modular monolith** with the following characteristics:

- **Clear module boundaries** — each module owns a domain (inventory, orders, reservations, etc.) and exposes a defined interface
- **Shared database** — all modules share a single PostgreSQL database with schema/table naming conventions to delineate ownership
- **In-process communication** — modules communicate via direct method calls within the same JVM process
- **Organized by domain** — package structure reflects domain modules, not technical layers

## Consequences

### Benefits

- **Simpler deployment and operations** — single deployable artifact, single database, no service mesh or inter-service networking
- **Transactional consistency** — cross-module operations (e.g., reservation + inventory update) can execute in a single database transaction
- **Faster development** — no need for API contracts between services, no serialization overhead, simpler debugging
- **Lower infrastructure cost** — one application, one database for MVP

### Risks and Trade-offs

- **Module boundary discipline** — the team must actively enforce module boundaries; without enforcement, the monolith can become a "big ball of mud"
- **Scaling constraints** — cannot independently scale individual modules; the entire application scales as one unit
- **Future extraction cost** — if a module needs to become a separate service, refactoring will be required (but the clean boundary makes this feasible)

### Future Considerations

- Modules can be extracted to independent services if scaling or organizational needs demand it
- The modular structure should make extraction straightforward if boundaries are maintained
