# STOCKGUARD

> **A high-concurrency inventory integrity and order reservation platform.**

STOCKGUARD is a production-oriented platform built to solve inventory discrepancies, stock-outs, and reservation conflicts for multi-facility retail and distribution enterprises. Built from the ground up on transactional rigor, mathematical ledger reconciliation, deterministic lock ordering, and strict multi-tenant boundary isolation.

---

## Architecture Highlights

* **Modular Monolith:** Clean domain boundaries without distributed transaction complexity (ADR-001).
* **PostgreSQL as Authoritative Source of Truth:** Database constraints, row-level locks, and append-only ledgers enforce consistency (ADR-002).
* **Dual-Persistence Strategy:** Spring Data JPA for declarative domain management alongside Spring JDBC for high-throughput concurrency and locking (ADR-008).
* **Cryptographic Token Lifecycle:** Short-lived JWT access tokens (15-minute expiry) signed with HMAC-SHA256, paired with opaque, database-backed rotating refresh tokens stored in `HttpOnly`, `SameSite=Strict` cookies (ADR-010).
* **Zero-Downtime Key Rotation:** Dynamic key registry supporting an active signing key alongside multiple retiring verification keys.
* **Database-Enforced Session & Tenant Validation:** Every authenticated request verifies active session status and dynamic organization membership directly against PostgreSQL (< 1 ms index lookup).
* **Principle of Least Privilege (Dual DB Roles):** Migration owner (`stockguard_owner`) owns schema and executes DDL via Flyway; runtime application (`stockguard_app`) is restricted strictly to least-privilege DML with table- and column-level protections.

---

## Implementation & Milestone Status

| Milestone | Scope | Status |
|---|---|:---:|
| **Milestone 1** | Scaffolding, Spring Boot 4.1.2, Java 21, Maven, Testcontainers | ✅ Complete |
| **Milestone 2** | Database Schema, V1 Core Tables, V2 Append-Only Triggers, V3 Auth Schema | ✅ Complete |
| **Milestone 3** | Identity Persistence, Organization Membership, Last-Admin Protection | ✅ Complete |
| **Milestone 4 — Phase 1** | JWT Signing, Key Rotation, Constant-Time Claims Verification | ✅ Complete |
| **Milestone 4 — Phase 2** | Session Lifecycle, Refresh Rotation, Replay Detection & Revocation | ✅ Complete |
| **Milestone 4 — Phase 3** | Auth REST APIs, CSRF Defense, CORS, Rate Limiting, 23-Permission RBAC | ✅ Complete |
| **Milestone 4 — Phase 4A** | Security Hardening, Production Config, Probes, Global Error Handling | ✅ Complete |
| **Milestone 4 — Phase 4B** | Final Regression, Release Readiness & Baseline Audit | ✅ Complete |
| **Milestone 5A** | Product Catalog, Warehouse Management & Core Inventory Engine | ✅ Complete |
| **Milestone 5B** | Advanced Inventory Operations (Multi-Item Inter-Warehouse Transfers) | ✅ Complete |

---

## Getting Started

### Prerequisites

* **Java 21** (JDK 21+)
* **Docker & Docker Compose** (for PostgreSQL)
* **Maven Wrapper** (included in repository: `./mvnw` or `mvnw.cmd`)

### 1. Start Database Infrastructure

Launch the PostgreSQL 16 container with dual-role initialization:

```bash
cd backend
docker-compose up -d
```

The database container will start on port `5432` (or configured `DB_PORT`) and automatically execute `init-db/init-roles.sql` to establish:
* `stockguard_owner` (migration credentials)
* `stockguard_app` (runtime DML credentials)

### 2. Run the Verification Suite

Run all unit, integration, and security filter tests against a fresh PostgreSQL Testcontainer:

```bash
cd backend
./mvnw clean verify
```

*(On Windows, use `mvnw.cmd clean verify -DDB_PORT=5433` if port 5432 is bound to local PostgreSQL).*

### 3. Run the Backend Application

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

---

## Configuration Reference

### Profiles

* **`dev` (default):** Configured for local development. Includes SQL formatting, relaxed development cookie security (`Secure=false` for `localhost`), debug logging, and dev database connection defaults.
* **`prod`:** Hardened production profile. Enforces `Secure=true`, `SameSite=Strict`, no committed fallback secrets, disabled SQL logging, Hikari connection pool tuning, and actuator information protection.
* **`test`:** Test slice profile using Testcontainers and mocked security contexts where appropriate.

### Key Environment Variables

| Variable | Description | Default (Dev) | Production Requirement |
|---|---|---|:---:|
| `SPRING_DATASOURCE_URL` | JDBC URL for application connection | `jdbc:postgresql://localhost:5432/stockguard` | **Required** |
| `SPRING_DATASOURCE_USERNAME` | Application DB user (role: `stockguard_app`) | `stockguard_app` | **Required** |
| `SPRING_DATASOURCE_PASSWORD` | Application DB password | `test_app_pass` | **Required** |
| `FLYWAY_URL` | JDBC URL for Flyway migrations | Same as datasource URL | **Required** |
| `FLYWAY_USER` | Migration DB user (role: `stockguard_owner`) | `stockguard_owner` | **Required** |
| `FLYWAY_PASSWORD` | Migration DB password | `test_owner_pass` | **Required** |
| `STOCKGUARD_JWT_ACTIVE_SECRET` | 256-bit Base64 HMAC signing secret | Provided dev secret | **Required** (Random 32+ bytes) |
| `STOCKGUARD_JWT_ACTIVE_KEY_ID` | Active JWT Key Identifier | `sg-2026-v1` | Recommended (`sg-prod-v1`) |
| `STOCKGUARD_COOKIE_SECURE` | Enforce HTTPS-only refresh cookies | `false` (in dev) | **`true`** |
| `STOCKGUARD_COOKIE_SAME_SITE` | Cookie SameSite policy | `Lax` (in dev) | **`Strict`** |
| `STOCKGUARD_CORS_ALLOWED_ORIGINS` | Comma-separated CORS allowed origins | `http://localhost:5173` | **Strict exact origins** |
| `STOCKGUARD_TRUSTED_PROXIES` | Upstream reverse proxy IPs / CIDR blocks | Empty (rejects forwarding) | Required if behind proxy |
| `PORT` | HTTP server port | `8080` | Optional |

---

## Security Architecture & API Protocol

### 1. Request Security Headers

Every API request must follow strict transport and security conventions:

* **`Authorization: Bearer <JWT>`**
  Mandatory for all authenticated endpoints. Carries user ID, session ID, and issued/expiration timestamps.
* **`X-Organization-Id: <UUID>`**
  Mandatory for all tenant-scoped business endpoints (e.g., `/api/organizations/current/**`). Missing header yields `400 Bad Request`. Inactive membership yields `403 Forbidden`.
* **`X-StockGuard-CSRF: 1`**
  Mandatory custom header for state-changing, cookie-bound requests (`POST /api/auth/refresh` and `POST /api/auth/logout`) to prevent Cross-Site Request Forgery (ADR-010).

### 2. Cookie Security Specification

Refresh tokens are never exposed in JSON response bodies:

* **Name:** `__Secure-stockguard_refresh` (in prod) / `stockguard_refresh` (in dev)
* **Path:** `/api/auth`
* **HttpOnly:** `true` (prohibits JavaScript access)
* **Secure:** `true` (enforced over HTTPS)
* **SameSite:** `Strict`

### 3. Centralized Error Handling

All controller errors and unhandled exceptions are intercepted by `GlobalExceptionHandler` and rendered in a standardized, RFC-compliant format without leaking internal stack traces:

```json
{
  "timestamp": "2026-11-13T12:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Field 'email' must not be blank",
  "path": "/api/auth/login"
}
```

---

## Implemented API Catalog

### 1. Authentication Endpoints (`/api/auth`)

| Method | Endpoint | Security / Headers | Description |
|---|---|---|---|
| `POST` | `/api/auth/login` | Public; Rate limited (5/min) | Authenticates credentials, sets HttpOnly refresh cookie, returns JWT. |
| `POST` | `/api/auth/refresh` | Cookie + `X-StockGuard-CSRF: 1`; Rate limited (20/min) | Rotates refresh token, detects replay attacks, returns fresh JWT. |
| `POST` | `/api/auth/logout` | Cookie or Bearer JWT + `X-StockGuard-CSRF: 1` | Revokes the current session and clears the refresh cookie. |
| `GET` | `/api/auth/me` | Bearer JWT | Returns current user profile and active organization memberships. |
| `POST` | `/api/auth/switch-org` | Bearer JWT | Validates membership in target tenant and returns organization context. |

### 2. Organization Membership Management (`/api/organizations/current`)

*All membership endpoints require `Authorization: Bearer <JWT>` and `X-Organization-Id: <UUID>`.*

| Method | Endpoint | Required Authority | Description |
|---|---|---|---|
| `GET` | `/api/organizations/current/memberships` | `membership:view` | Lists all active members and assigned roles for the current organization. |
| `POST` | `/api/organizations/current/members` | `membership:manage` | Adds a user to the organization with an assigned role (`ADMIN`, `MANAGER`, `OPERATOR`, `VIEWER`). |
| `PUT` | `/api/organizations/current/members/{userId}/role` | `membership:manage` | Updates an existing member's role (enforces atomic Last-Admin protection). |
| `DELETE` | `/api/organizations/current/members/{userId}` | `membership:manage` | Soft-deactivates an organization membership (enforces Last-Admin protection). |

### 3. Operational & Health Probes (`/actuator`)

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/actuator/health` | Overall system health status. |
| `GET` | `/actuator/health/liveness` | Container orchestration liveness probe. |
| `GET` | `/actuator/health/readiness` | Container orchestration readiness probe (validates DB connectivity). |
| `GET` | `/actuator/info` | Application build and version metadata. |

---

## Role-Based Access Control (RBAC) Matrix

STOCKGUARD enforces a deterministic 23-permission authorization matrix across four tenant-scoped roles:

| Domain | Permission Code | ADMIN | MANAGER | OPERATOR | VIEWER |
|---|---|:---:|:---:|:---:|:---:|
| **Dashboard** | `dashboard:view` | ✅ | ✅ | ✅ | ✅ |
| **Reports** | `reports:view` | ✅ | ✅ | ✅ | ✅ |
| **Inventory** | `inventory:view` | ✅ | ✅ | ✅ | ✅ |
| | `inventory:receive` | ✅ | ✅ | ✅ | ❌ |
| | `inventory:adjust` | ✅ | ✅ | ✅ | ❌ |
| | `inventory:reconcile` | ✅ | ✅ | ❌ | ❌ |
| **Product** | `product:view` | ✅ | ✅ | ✅ | ✅ |
| | `product:manage` | ✅ | ✅ | ❌ | ❌ |
| **Warehouse** | `warehouse:view` | ✅ | ✅ | ✅ | ✅ |
| | `warehouse:manage` | ✅ | ✅ | ❌ | ❌ |
| **Transfer** | `transfer:view` | ✅ | ✅ | ✅ | ✅ |
| | `transfer:execute` | ✅ | ✅ | ✅ | ❌ |
| **Order** | `order:view` | ✅ | ✅ | ✅ | ✅ |
| | `order:create` | ✅ | ✅ | ✅ | ❌ |
| | `order:confirm` | ✅ | ✅ | ✅ | ❌ |
| | `order:fulfill` | ✅ | ✅ | ✅ | ❌ |
| | `order:cancel` | ✅ | ✅ | ✅ | ❌ |
| **Reservation** | `reservation:view` | ✅ | ✅ | ✅ | ✅ |
| | `reservation:create` | ✅ | ✅ | ✅ | ❌ |
| | `reservation:release` | ✅ | ✅ | ✅ | ❌ |
| **Membership** | `membership:view` | ✅ | ✅ | ✅ | ✅ |
| | `membership:manage` | **✅ Only** | ❌ | ❌ | ❌ |
| **Audit** | `audit:view` | ✅ | ✅ | ❌ | ❌ |

### Last-Admin Protection Guarantee

To prevent organizations from becoming permanently orphaned, demoting or deactivating an administrator requires acquiring an atomic row-level lock on the tenant row (`SELECT id FROM organizations WHERE id = :orgId FOR UPDATE`). If the target user is the sole remaining active `ADMIN`, the operation is rejected with HTTP `409 Conflict` (`CANNOT_DEMOTE_SOLE_ADMIN`).

---

## Core Inventory Engine & Domain APIs (Milestone 5A)

### 1. Product Catalog & Warehouse Management

* **Product Management (`/api/products`)**:
  * `POST /api/products`: Create a product (`sku`, `name`, `description`). SKU is automatically normalized (`upper(trim(sku))`). Unique normalized SKU per tenant (`uq_products_org_sku`).
  * `GET /api/products`: Search and list products with tenant isolation (`q`, `activeOnly` query parameters).
  * `GET /api/products/{id}`: Retrieve product details by ID within the current tenant.
  * `PUT /api/products/{id}`: Update mutable details (`name`, `description`). SKU is immutable.
  * `DELETE /api/products/{id}`: Soft deactivation (`active = false`). Preserves referential integrity for historical ledgers.
  * *Required permissions*: `product:manage` (mutations), `product:view` (read).

* **Warehouse Management (`/api/warehouses`)**:
  * `POST /api/warehouses`: Create a facility (`code`, `name`, `location`, `priority`). Code is normalized (`upper(trim(code))`). Unique normalized code per tenant (`uq_warehouses_org_code`).
  * `GET /api/warehouses`: List active warehouses ordered by priority ascending (`priority ASC, name ASC`).
  * `GET /api/warehouses/{id}`: Retrieve warehouse details by ID within the current tenant.
  * `PUT /api/warehouses/{id}`: Update warehouse metadata (`name`, `location`, `priority`). Code is immutable.
  * `DELETE /api/warehouses/{id}`: Soft deactivation (`active = false`).
  * *Required permissions*: `warehouse:manage` (mutations), `warehouse:view` (read).

### 2. Transactional Inventory Engine & Dual-Delta Ledger

STOCKGUARD's inventory balances represent materialized views that are strictly derived from and reconcilable with the append-only ledger:

$$\text{Available} = \text{On Hand} - \text{Reserved}$$

* **Atomic Stock Operations**:
  * `POST /api/inventory/opening-balances`: Sets initial on-hand stock for a product in a warehouse. Creates an `OPENING_BALANCE` transaction and ledger entry. Rejected if an opening balance has already been recorded historically for the given product and warehouse, even if current on-hand stock has subsequently returned to zero.
  * `POST /api/inventory/receipts`: Ingests inbound stock receipts. Increments `on_hand` and inserts an immutable `RECEIPT` ledger entry.
  * `POST /api/inventory/adjustments`: Records cycle count corrections and manual shrinkage adjustments. Supports positive or negative deltas with mandatory textual reason and actor attribution. Fails closed (`409 Conflict`) if an adjustment would cause $\text{on\_hand} < 0$ or $\text{on\_hand} < \text{reserved}$.
  * `GET /api/inventory/balances`: Lists live balances across all facilities.
  * `GET /api/inventory/balances/{productId}/{warehouseId}`: Fetches specific balance.
  * `GET /api/inventory/transactions`: Lists recent inventory transactions and their associated ledger lines.
  * `GET /api/inventory/transactions/{id}`: Retrieves specific transaction and its ledger entries.
  * `GET /api/inventory/reconciliation`: Read-only mathematical reconciliation comparing current balance snapshots against `SUM(on_hand_delta)` and `SUM(reserved_delta)` across the entire historical ledger. Detects `DELTA_MISMATCH`, `MISSING_BALANCE_ROW`, and `ORPHAN_BALANCE_ROW`.
  * *Required permissions*: `inventory:receive`, `inventory:adjust`, `inventory:view`, `inventory:reconcile`.

### 3. Concurrency, Locking & Idempotency Guarantees

* **Canonical Lock Ordering (ADR-003)**:
  * Multi-item or multi-facility inventory mutations sort locks strictly by:
    $$\text{product\_id ASC}, \text{warehouse\_id ASC}$$
  * Row locks are acquired via `SELECT ... FOR UPDATE` using Spring JDBC before any ledger insertions or balance modifications occur.
  * *Concurrency Scope & Deadlock Semantics*: Canonical locking establishes deterministic acquisition order for the specified warehouse, product and inventory-balance rows. It substantially reduces deadlock risk within those lock domains, but does not guarantee system-wide deadlock freedom. Eligible transient database failures are handled through fresh-transaction retries.
* **Database Ledger Immutability (ADR-002)**:
  * Triggers defined in `V2__enforce_append_only_ledgers.sql` reject all `UPDATE`, `DELETE`, and `TRUNCATE` operations on `inventory_ledger_entries`. Ledger history is append-only and cryptographically auditable.
* **Distributed Idempotency (ADR-007)**:
  * Critical write APIs support the `Idempotency-Key` HTTP header.
  * Uses `idempotency_records` table with row-level locks and SHA-256 payload hashing to prevent duplicate mutations, detect in-flight concurrent execution, and safely replay prior responses for identical retries.

### 4. Advanced Inventory Operations: Warehouse Transfers (Milestone 5B)

STOCKGUARD supports immediate, atomic multi-item inter-warehouse inventory transfers without intermediate staging states or phantom stock drift:

* **Transfer REST APIs (`/api/transfers`)**:
  * `POST /api/transfers`: Executes an atomic multi-item transfer between two distinct warehouses within the current tenant organization. Accepts `sourceWarehouseId`, `destinationWarehouseId`, `notes`, and a non-empty list of items (`productId`, `quantity`).
  * `GET /api/transfers`: Lists recent transfers within the current tenant organization (supports optional `warehouseId` filter and `limit` parameter, default 50, max 200).
  * `GET /api/transfers/{transferId}`: Retrieves specific transfer details, line items, and associated inventory transaction reference.
  * *Required permissions*: `transfer:execute` (`ADMIN`, `MANAGER`, `OPERATOR`), `transfer:view` (`ADMIN`, `MANAGER`, `OPERATOR`, `VIEWER`).

* **Transfer Transaction & Integrity Invariants**:
  1. **Facility Validation**: Rejects transfers if source or destination warehouse is not found or is inactive.
  2. **Distinct Facilities**: Source and destination warehouses must be different (`chk_transfers_distinct_warehouses`).
  3. **Product Validation**: All products in transfer lines must exist, belong to the tenant, and be active.
  4. **Strict Positivity & Uniqueness**: Item quantities must be strictly positive (`quantity > 0`), and duplicate product IDs within a single transfer request are rejected.
  5. **Destination Row Pre-Creation**: Balance rows at the destination warehouse are idempotently pre-created (`ensureBalanceRowExists` via `INSERT ... ON CONFLICT DO NOTHING`) prior to locking, ensuring transfers succeed even for products stocked at a facility for the first time.
  6. **Canonical Balance Locking (ADR-003)**: Row-level locks (`SELECT ... FOR UPDATE`) are acquired across all source and destination balances in global canonical order:
     $$\text{product\_id ASC}, \text{warehouse\_id ASC}$$
  7. **Reserved Stock Protection**: Transfers strictly verify available stock:
     $$\text{Available} = \text{On Hand} - \text{Reserved} \ge \text{Quantity}$$
     Reserved stock allocated to customer orders is never consumed by transfers.
  8. **Physical Stock Conservation**: Source balance is debited (`on_hand -= quantity`) and destination balance is credited (`on_hand += quantity`) atomically; net physical stock across facilities is strictly conserved ($\Delta \text{OnHand} = 0$).
  9. **Header & Item Persistence**: Creates one `transfers` header and $N$ `transfer_items` rows.
  10. **Inventory Transaction Linking**: Creates one `inventory_transactions` header with `transaction_type = 'TRANSFER'`, `reference_type = 'TRANSFER'`, and `reference_id = transfer.id`.
  11. **Deterministic Dual-Delta Ledger Entries**: Creates exactly $2N$ sequential ledger entries:
      * Line $j$ OUT: `entry_number = 2j - 1`, $\Delta \text{on\_hand} = -\text{qty}$, $\Delta \text{reserved} = 0$, `transfer_direction = 'OUT'`.
      * Line $j$ IN: `entry_number = 2j`, $\Delta \text{on\_hand} = +\text{qty}$, $\Delta \text{reserved} = 0$, `transfer_direction = 'IN'`.
      * All transfer ledger entries enforce $\Delta \text{reserved} = 0$.
  12. **Pre-Commit Integrity Verification**: Verifies pair completeness ($1 \text{ OUT} + 1 \text{ IN}$ per item), matching warehouses, correct products, opposite deltas, and zero net delta before transaction commit.
  13. **Distributed Idempotency & Concurrency Retries**: Integrates with `IdempotencyService`. Concurrent identical requests wait for in-flight execution and replay the committed response; payload mismatches return HTTP 409 Conflict. If transient PostgreSQL deadlocks or serialization failures occur (`40P01`, `40001`, `55P03`), the service automatically retries in a fresh transaction with jittered backoff.
  14. **Deadlock Limitations Notice**: Canonical locking establishes deterministic acquisition order for the specified warehouse, product and inventory-balance rows. It substantially reduces deadlock risk within those lock domains, but does not guarantee system-wide deadlock freedom. Eligible transient database failures are handled through fresh-transaction retries.

---

## Testing & Verification

The test suite exercises all layers of the security, persistence, and authorization architecture using JUnit 5, Mockito, Spring MVC Test, and Testcontainers (PostgreSQL 16):

```bash
cd backend
./mvnw clean verify
```

* **Unit Tests:** JWT token encoding/decoding, key rotation, claims extraction, rate limit window counters, CIDR subnet matching, password hashing, and exception handling.
* **Slice Tests:** WebMvc slice tests for controllers, validation errors, and custom headers.
* **Integration Tests:** End-to-end filter chain scenarios, database-backed session revocation, replay detection with family revocation, tenant context enforcement, and concurrent last-admin protection.

---

## Repository Structure

```
stockguard/
├── backend/
│   ├── init-db/
│   │   └── init-roles.sql            # PostgreSQL dual-role privilege initialization
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com/stockguard/
│   │   │   │   ├── audit/            # Security and operational audit logging
│   │   │   │   ├── common/           # Shared identifiers and entities
│   │   │   │   ├── inventory/        # Balances, ledger entries, transactions, reconciliation
│   │   │   │   ├── organization/     # Tenants, memberships, last-admin protection
│   │   │   │   ├── product/          # Product catalog and normalized SKU management
│   │   │   │   ├── security/         # JWT, sessions, filters, RBAC, exception handlers
│   │   │   │   ├── shared/           # Common exceptions and distributed idempotency engine
│   │   │   │   ├── transfer/         # Multi-item inter-warehouse transfers, DTOs, service, controller
│   │   │   │   └── warehouse/        # Facility management, priority routing, codes
│   │   │   └── resources/
│   │   │       ├── db/migration/     # Flyway immutable migrations (V1, V2, V3)
│   │   │       ├── application.yml   # Base application configuration
│   │   │       ├── application-dev.yml # Local development overrides
│   │   │       └── application-prod.yml # Hardened production configuration
│   │   └── test/                     # 245+ unit, integration, and security tests
│   ├── docker-compose.yml            # Local PostgreSQL 16 container definition
│   └── pom.xml                       # Maven build descriptor
├── docs/                             # Architecture Decision Records (ADR 001-011) & specs
└── README.md
```
