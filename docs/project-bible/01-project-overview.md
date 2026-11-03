# STOCKGUARD — Project Overview

## Project Name

**STOCKGUARD**

## Purpose

STOCKGUARD is a high-concurrency inventory integrity and order reservation platform designed for retailers and distributors. It provides reliable, transactionally consistent inventory management across multiple warehouses with full auditability.

## Primary Engineering Objective

Demonstrate strong backend engineering through:

- **Transactional consistency** — atomic inventory operations backed by PostgreSQL
- **Concurrent inventory operations** — safe handling of simultaneous stock mutations
- **Database integrity** — enforced invariants at the schema and application level
- **Idempotency** — safe retry semantics for all mutating API operations
- **Auditability** — append-only ledger and audit logging for every state change
- **Testing** — comprehensive unit, integration, and performance test coverage
- **Performance verification** — load testing to validate behavior under concurrency

## Target Users

Retailers and distributors managing multi-warehouse inventory who require:

- Accurate, real-time inventory balances across warehouses
- Order reservation guarantees with expiration semantics
- Full audit trails for inventory movements
- An operations dashboard for monitoring inventory health

## Technology Stack Summary

| Layer | Technology | Status |
|---|---|---|
| Language | Java 21 | **Accepted Decision** |
| Framework | Spring Boot | **Accepted Decision** |
| Build Tool | Maven | **Accepted Decision** |
| Database | PostgreSQL | **Accepted Decision** |
| Frontend | React, TypeScript, Vite | **Accepted Decision** |
| Containerization | Docker | **Accepted Decision** |
| Unit/Integration Testing | JUnit 5, Testcontainers | **Accepted Decision** |
| Performance Testing | k6 | **Accepted Decision** |

## Architecture Summary

| Aspect | Decision | Status |
|---|---|---|
| Architecture style | Modular monolith | **Accepted Decision** |
| Multi-organization scope | Single-organization launch with organization-aware schema; full SaaS multi-tenancy deferred | **Accepted Decision** |
| API approach | API-first backend (REST) | **Accepted Decision** |
| Frontend | Operations dashboard (React/TypeScript/Vite) | **Accepted Decision** |
| Data authority | PostgreSQL is the single source of truth for inventory state | **Accepted Decision** |

> [!IMPORTANT]
> STOCKGUARD follows a modular monolith architecture — not microservices. All domain modules run within a single deployable unit, communicating via in-process calls. This is a deliberate architectural decision to reduce operational complexity while maintaining clear module boundaries.

## Current Status

**Architecture and design phase — implementation has not yet started.**

All decisions documented in the project bible are either accepted architectural decisions, proposed designs awaiting validation, or unresolved open questions. No code has been written, no tests have been executed, and no infrastructure has been provisioned.
