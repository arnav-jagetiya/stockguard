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
│   │   │   │   ├── organization/     # Tenants, memberships, last-admin protection
│   │   │   │   └── security/         # JWT, sessions, filters, RBAC, exception handlers
│   │   │   └── resources/
│   │   │       ├── db/migration/     # Flyway immutable migrations (V1, V2, V3)
│   │   │       ├── application.yml   # Base application configuration
│   │   │       ├── application-dev.yml # Local development overrides
│   │   │       └── application-prod.yml # Hardened production configuration
│   │   └── test/                     # 185+ unit, integration, and security tests
│   ├── docker-compose.yml            # Local PostgreSQL 16 container definition
│   └── pom.xml                       # Maven build descriptor
├── docs/                             # Architecture Decision Records (ADR 001-011) & specs
└── README.md
```
