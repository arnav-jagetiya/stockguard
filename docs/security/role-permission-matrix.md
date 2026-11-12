# STOCKGUARD — Role and Permission Reference Matrix

> **Document Status:** PROPOSED ARCHITECTURE FOR MILESTONE 3 (FINAL TECHNICAL SIGN-OFF REVISIONS)
> **Target Version:** Milestone 3 (Authorization Foundation)
> **Authoritative References:** [ADR-011](../architecture/adr/ADR-011-authorization-and-organization-access-control.md), [Security Architecture](security-architecture.md)

---

## 1. Role Definitions & Hierarchy

STOCKGUARD defines four tenant-scoped RBAC roles stored directly in `organization_memberships.role`:

```
               [ADMIN]
                  │
        ┌─────────┴─────────┐
        ▼                   ▼
    [MANAGER]           [OPERATOR]
        │                   │
        └─────────┬─────────┘
                  ▼
              [VIEWER]
```

* **`ADMIN` (Tenant Administrator):**
  * Highest authority within an organization.
  * Owns tenant membership governance, user provisioning, role assignments, facility configurations, catalog definitions, and operational workflows.
  * The only role permitted to perform membership and user administration.
* **`MANAGER` (Operations & Facility Manager):**
  * Responsible for inventory catalog, storage facility configurations, and high-level inventory control.
  * Can manage products, warehouses, execute stock adjustments, view audit logs, and trigger mathematical reconciliations.
  * Prohibited from managing users and organization memberships.
* **`OPERATOR` (Warehouse & Fulfillment Operator):**
  * Responsible for daily physical and logical inventory execution.
  * Can receive stock, execute approved adjustments, perform inter-warehouse transfers, create and fulfill orders, and release reservations.
  * Prohibited from modifying product catalog definitions, warehouse facilities, audit logs, or users.
* **`VIEWER` (Read-Only Auditor / Observer):**
  * Strictly read-only access to operational dashboards, reports, stock positions, and catalog listings.
  * Cannot execute any mutating actions (`POST`, `PUT`, `DELETE`).

---

## 2. Approved Business Capability Matrix

The following matrix represents the owner-approved functional capabilities across the four roles:

| Capability | ADMIN | MANAGER | OPERATOR | VIEWER |
|---|:---:|:---:|:---:|:---:|
| **View dashboard and inventory** | Yes | Yes | Yes | Yes |
| **Manage users and memberships** | **Yes** | No | No | No |
| **Manage products and warehouses** | **Yes** | **Yes** | No | No |
| **Receive and adjust inventory** | **Yes** | **Yes** | **Yes** | No |
| **Create and execute transfers** | **Yes** | **Yes** | **Yes** | No |
| **Create and manage orders** | **Yes** | **Yes** | **Yes** | No |
| **Fulfill and cancel orders** | **Yes** | **Yes** | **Yes** | No |
| **View audit logs** | **Yes** | **Yes** | No | No |

---

## 3. Granular Permission Catalog

Granular permissions follow the canonical format `<domain>:<action>`. Each role is granted a deterministic set of authorities:

| Permission Code | Domain | Description | ADMIN | MANAGER | OPERATOR | VIEWER |
|---|---|---|:---:|:---:|:---:|:---:|
| `dashboard:view` | Dashboard | View operational KPIs, summary stats, alert cards | Yes | Yes | Yes | Yes |
| `reports:view` | Reports | View stock movement summaries and valuation reports | Yes | Yes | Yes | Yes |
| `inventory:view` | Inventory | Query stored stock balances (`on_hand`, `reserved`, `available`) | Yes | Yes | Yes | Yes |
| `inventory:receive` | Inventory | Record stock receipts and opening balance transactions | Yes | Yes | Yes | No |
| `inventory:adjust` | Inventory | Execute physical stock adjustments with reason notes | Yes | Yes | Yes | No |
| `inventory:reconcile` | Inventory | Execute mathematical balance reconciliation against ledger | Yes | Yes | No | No |
| `product:view` | Product | Query catalog items and detailed product specifications | Yes | Yes | Yes | Yes |
| `product:manage` | Product | Create new products, update catalog details, soft-deactivate SKUs | Yes | Yes | No | No |
| `warehouse:view` | Warehouse | List warehouses, locations, and allocation priority ranks | Yes | Yes | Yes | Yes |
| `warehouse:manage` | Warehouse | Create warehouses, update facility priority, deactivate locations | Yes | Yes | No | No |
| `transfer:view` | Transfer | View inter-warehouse transfer records and item lines | Yes | Yes | Yes | Yes |
| `transfer:execute` | Transfer | Initiate and execute immediate stock transfers between facilities | Yes | Yes | Yes | No |
| `order:view` | Order | View customer orders, lines, and lifecycle statuses | Yes | Yes | Yes | Yes |
| `order:create` | Order | Create draft orders and edit line item snapshots | Yes | Yes | Yes | No |
| `order:confirm` | Order | Confirm draft order (`PENDING → CONFIRMED`), protecting holds | Yes | Yes | Yes | No |
| `order:fulfill` | Order | Fulfill order lines (`CONFIRMED → FULFILLED`), consuming stock | Yes | Yes | Yes | No |
| `order:cancel` | Order | Cancel orders (`→ CANCELLED`), releasing reservations | Yes | Yes | Yes | No |
| `reservation:view` | Reservation | View active reservation holds and allocations | Yes | Yes | Yes | Yes |
| `reservation:create` | Reservation | Allocate inventory holds for pending orders | Yes | Yes | Yes | No |
| `reservation:release` | Reservation | Manually release an active reservation hold | Yes | Yes | Yes | No |
| `membership:view` | Membership | List organization members and their assigned roles | Yes | Yes | Yes | Yes |
| `membership:manage` | Membership | Invite users, assign roles, activate/deactivate memberships | **Yes** | No | No | No |
| `audit:view` | Audit | Query immutable audit logs and historical event trails | Yes | Yes | No | No |

---

## 4. Authoritative Database State & Explicit Context Resolution

* **Source of Truth:** Permissions are mapped deterministically from the role resolved dynamically from the PostgreSQL `organization_memberships` table for each organization-scoped request.
* **Dual-Bound Session & Identity Validation:**
  The filter chain queries `auth_sessions` and `users`:
  ```sql
  SELECT s.revoked, s.expires_at, u.active AS user_active
  FROM auth_sessions s
  JOIN users u ON s.user_id = u.id
  WHERE s.id = :sessionId
    AND s.user_id = :userId;
  ```
  Asserts that `s.id = :sessionId` and `s.user_id = :userId`, `s.revoked = FALSE`, `s.expires_at > NOW()`, and `u.user_active = TRUE`.
* **Endpoint Classification & Explicit Context:**
  * **User-Scoped Endpoints:** (`/api/auth/me`, `/api/auth/switch-org`, `/api/auth/logout`) require active user and session validation, but bypass organization context requirements.
  * **Organization-Scoped Endpoints:** (`/api/products/**`, `/api/inventory/**`, etc.) mandate explicit tenant declaration via the `X-Organization-Id` request header (or route param). **No silent session fallbacks.** Missing header returns HTTP `400 Bad Request` (`MISSING_ORGANIZATION_CONTEXT`).
  * **PostgreSQL Membership Assertion:**
    ```sql
    SELECT m.role, m.active AS membership_active
    FROM organization_memberships m
    WHERE m.user_id = :userId
      AND m.organization_id = :requestedOrgId
      AND m.active = TRUE;
    ```
    Missing or inactive membership returns HTTP `403 Forbidden` (`INSUFFICIENT_TENANT_MEMBERSHIP`).
* **Organization Switching Semantics:**
  - `POST /api/auth/switch-org` validates that the user holds an active membership in the requested target organization and returns the tenant's permissions and role.
  - The persisted session in `auth_sessions` is **not** mutated; client tabs independently declare their active organization via `X-Organization-Id`, enabling multiple tabs to operate safely in distinct organizations without state collision.
* **Revocation Timing:** Authorization revocation is effective on the next authorization check after the relevant database transaction commits.
* **Performance Strategy:** An indexed query on `(user_id, organization_id)` targets $< 1$ ms execution time on typical production database infrastructure. This is an engineering performance target, not a verified benchmark.
* **Fail-Closed Security:** Database connection errors or validation timeouts cause the request to fail closed (HTTP 500 / 503), preventing unauthorized execution.

---

## 5. Complete Endpoint-to-Permission Mapping

Every REST API endpoint defined in `api-design-status.md` maps to explicit authorization rules:

### 1. Authentication & Tenant Access Endpoints
| HTTP Method | Endpoint Path | Transport & Scope | Allowed Roles | Spring Security Annotation |
|---|---|---|---|---|
| `POST` | `/api/auth/login` | Public JSON body | Anonymous | `permitAll()` |
| `POST` | `/api/auth/refresh` | `__Secure-` Cookie + CSRF Header | Anonymous / Cookie-bound | `permitAll()` (with CSRF verification) |
| `POST` | `/api/auth/logout` | `__Secure-` Cookie + CSRF Header (User-Scoped) | Authenticated | `authenticated()` |
| `POST` | `/api/auth/switch-org` | Bearer JWT Header (User-Scoped) | Authenticated | `authenticated()` |
| `GET` | `/api/organizations/current/memberships` | Bearer JWT Header (Org-Scoped) | ADMIN, MANAGER, OPERATOR, VIEWER | `@PreAuthorize("hasAuthority('membership:view')")` |
| `POST` | `/api/organizations/current/members` | Bearer JWT Header (Org-Scoped) | **ADMIN only** | `@PreAuthorize("hasAuthority('membership:manage')")` |
| `PUT` | `/api/organizations/current/members/{userId}/role` | Bearer JWT Header (Org-Scoped) | **ADMIN only** | `@PreAuthorize("hasAuthority('membership:manage')")` |
| `DELETE` | `/api/organizations/current/members/{userId}` | Bearer JWT Header (Org-Scoped) | **ADMIN only** | `@PreAuthorize("hasAuthority('membership:manage')")` |

### 2. Product Catalog Endpoints
| HTTP Method | Endpoint Path | Required Authority | Allowed Roles | Spring Security Annotation |
|---|---|---|---|---|
| `GET` | `/api/products` | `product:view` | ADMIN, MANAGER, OPERATOR, VIEWER | `@PreAuthorize("hasAuthority('product:view')")` |
| `GET` | `/api/products/{id}` | `product:view` | ADMIN, MANAGER, OPERATOR, VIEWER | `@PreAuthorize("hasAuthority('product:view')")` |
| `POST` | `/api/products` | `product:manage` | ADMIN, MANAGER | `@PreAuthorize("hasAuthority('product:manage')")` |
| `PUT` | `/api/products/{id}` | `product:manage` | ADMIN, MANAGER | `@PreAuthorize("hasAuthority('product:manage')")` |
| `DELETE` | `/api/products/{id}` | `product:manage` | ADMIN, MANAGER | `@PreAuthorize("hasAuthority('product:manage')")` |

### 3. Warehouse Facility Endpoints
| HTTP Method | Endpoint Path | Required Authority | Allowed Roles | Spring Security Annotation |
|---|---|---|---|---|
| `GET` | `/api/warehouses` | `warehouse:view` | ADMIN, MANAGER, OPERATOR, VIEWER | `@PreAuthorize("hasAuthority('warehouse:view')")` |
| `GET` | `/api/warehouses/{id}` | `warehouse:view` | ADMIN, MANAGER, OPERATOR, VIEWER | `@PreAuthorize("hasAuthority('warehouse:view')")` |
| `POST` | `/api/warehouses` | `warehouse:manage` | ADMIN, MANAGER | `@PreAuthorize("hasAuthority('warehouse:manage')")` |
| `PUT` | `/api/warehouses/{id}` | `warehouse:manage` | ADMIN, MANAGER | `@PreAuthorize("hasAuthority('warehouse:manage')")` |
| `DELETE` | `/api/warehouses/{id}` | `warehouse:manage` | ADMIN, MANAGER | `@PreAuthorize("hasAuthority('warehouse:manage')")` |

### 4. Inventory Operations & Ledger Endpoints
| HTTP Method | Endpoint Path | Required Authority | Allowed Roles | Spring Security Annotation |
|---|---|---|---|---|
| `GET` | `/api/inventory/balances` | `inventory:view` | ADMIN, MANAGER, OPERATOR, VIEWER | `@PreAuthorize("hasAuthority('inventory:view')")` |
| `GET` | `/api/inventory/ledger` | `inventory:view` | ADMIN, MANAGER, OPERATOR, VIEWER | `@PreAuthorize("hasAuthority('inventory:view')")` |
| `POST` | `/api/inventory/receive` | `inventory:receive` | ADMIN, MANAGER, OPERATOR | `@PreAuthorize("hasAuthority('inventory:receive')")` |
| `POST` | `/api/inventory/adjust` | `inventory:adjust` | ADMIN, MANAGER, OPERATOR | `@PreAuthorize("hasAuthority('inventory:adjust')")` |
| `POST` | `/api/inventory/reconcile` | `inventory:reconcile` | ADMIN, MANAGER | `@PreAuthorize("hasAuthority('inventory:reconcile')")` |

### 5. Order Management & Fulfillment Endpoints
| HTTP Method | Endpoint Path | Required Authority | Allowed Roles | Spring Security Annotation |
|---|---|---|---|---|
| `GET` | `/api/orders` | `order:view` | ADMIN, MANAGER, OPERATOR, VIEWER | `@PreAuthorize("hasAuthority('order:view')")` |
| `GET` | `/api/orders/{id}` | `order:view` | ADMIN, MANAGER, OPERATOR, VIEWER | `@PreAuthorize("hasAuthority('order:view')")` |
| `POST` | `/api/orders` | `order:create` | ADMIN, MANAGER, OPERATOR | `@PreAuthorize("hasAuthority('order:create')")` |
| `PUT` | `/api/orders/{id}` | `order:create` | ADMIN, MANAGER, OPERATOR | `@PreAuthorize("hasAuthority('order:create')")` |
| `POST` | `/api/orders/{id}/confirm` | `order:confirm` | ADMIN, MANAGER, OPERATOR | `@PreAuthorize("hasAuthority('order:confirm')")` |
| `POST` | `/api/orders/{id}/fulfill` | `order:fulfill` | ADMIN, MANAGER, OPERATOR | `@PreAuthorize("hasAuthority('order:fulfill')")` |
| `POST` | `/api/orders/{id}/cancel` | `order:cancel` | ADMIN, MANAGER, OPERATOR | `@PreAuthorize("hasAuthority('order:cancel')")` |

### 6. Inventory Reservation Endpoints
| HTTP Method | Endpoint Path | Required Authority | Allowed Roles | Spring Security Annotation |
|---|---|---|---|---|
| `GET` | `/api/reservations/{id}` | `reservation:view` | ADMIN, MANAGER, OPERATOR, VIEWER | `@PreAuthorize("hasAuthority('reservation:view')")` |
| `POST` | `/api/reservations` | `reservation:create` | ADMIN, MANAGER, OPERATOR | `@PreAuthorize("hasAuthority('reservation:create')")` |
| `POST` | `/api/reservations/{id}/release` | `reservation:release` | ADMIN, MANAGER, OPERATOR | `@PreAuthorize("hasAuthority('reservation:release')")` |

### 7. Warehouse Stock Transfer Endpoints
| HTTP Method | Endpoint Path | Required Authority | Allowed Roles | Spring Security Annotation |
|---|---|---|---|---|
| `GET` | `/api/transfers` | `transfer:view` | ADMIN, MANAGER, OPERATOR, VIEWER | `@PreAuthorize("hasAuthority('transfer:view')")` |
| `GET` | `/api/transfers/{id}` | `transfer:view` | ADMIN, MANAGER, OPERATOR, VIEWER | `@PreAuthorize("hasAuthority('transfer:view')")` |
| `POST` | `/api/transfers` | `transfer:execute` | ADMIN, MANAGER, OPERATOR | `@PreAuthorize("hasAuthority('transfer:execute')")` |

### 8. Dashboard, Reporting & Audit Endpoints
| HTTP Method | Endpoint Path | Required Authority | Allowed Roles | Spring Security Annotation |
|---|---|---|---|---|
| `GET` | `/api/dashboard/summary` | `dashboard:view` | ADMIN, MANAGER, OPERATOR, VIEWER | `@PreAuthorize("hasAuthority('dashboard:view')")` |
| `GET` | `/api/reports/inventory` | `reports:view` | ADMIN, MANAGER, OPERATOR, VIEWER | `@PreAuthorize("hasAuthority('reports:view')")` |
| `GET` | `/api/audit-logs` | `audit:view` | ADMIN, MANAGER | `@PreAuthorize("hasAuthority('audit:view')")` |

---

## 6. Administrative Membership Boundary Protections

### 1. Mandatory `ADMIN` Role Enforcement
Only users possessing the `membership:manage` authority (strictly granted to `ADMIN`) may invoke membership APIs. Attempts by `MANAGER`, `OPERATOR`, or `VIEWER` return HTTP `403 Forbidden` (`INSUFFICIENT_PERMISSIONS`).

### 2. Concurrency-Safe Last-Admin Protection
To guarantee that an organization is never left without an active administrator, administrative mutations serialize via a row-level lock on the `organizations` table:
1. `SELECT id FROM organizations WHERE id = :orgId FOR UPDATE;`
2. `SELECT COUNT(*) FROM organization_memberships WHERE organization_id = :orgId AND role = 'ADMIN' AND active = TRUE;`
3. If the target user is currently an active `ADMIN`, and active administrator count $\le 1$, demotion or deactivation is rejected with HTTP `400 Bad Request` or `409 Conflict` (`CANNOT_DEMOTE_SOLE_ADMIN`).
4. This atomic row lock prevents concurrent administrators from simultaneously demoting each other.

### 3. Cross-Tenant Membership Isolation
An administrator can only manage memberships where `organization_id = OrganizationContextHolder.getOrganizationId()`. Attempts to modify a membership from another organization are rejected by tenant filtering.

## 7. Database Privilege Boundary: Runtime Application Role vs. Migration Owner Role

In addition to application-level Spring Security role checks (`hasAuthority(...)`), STOCKGUARD enforces strict relational privilege separation directly in PostgreSQL:

### 1. Database Role Separation
* **`stockguard_owner` (Migration / Schema Owner):**
  - Owns all schemas, tables, sequences, functions, and triggers.
  - Possesses full DDL rights (`CREATE`, `ALTER`, `DROP`).
  - Used exclusively by Flyway migration runners during automated CI/CD deployments.
  - Never used by the live Spring Boot application at runtime.
* **`stockguard_app` (Runtime Application Role):**
  - Dedicated non-owner runtime database user for the Spring Boot application connection pool.
  - Configured `WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;`.
  - Has NO membership in `stockguard_owner` and cannot execute `SET ROLE stockguard_owner`.
  - Cannot disable or bypass triggers (no `session_replication_role` superuser privileges).
  - Has NO DDL rights (`CANNOT ALTER TABLE`, `CANNOT DROP TABLE`, `CANNOT CREATE FUNCTION`, `CANNOT DISABLE TRIGGER`).
  - Restricted strictly to least-privilege DML operations.

### 2. Table-by-Table Privilege Matrix for `stockguard_app`

| Table Name | `SELECT` | `INSERT` | `UPDATE` | `DELETE` | Integrity Model & Boundary Controls |
|:---|:---:|:---:|:---:|:---:|:---|
| `users` | Granted | Granted | Granted | Prohibited | Soft-delete only (`active = FALSE`) |
| `organizations` | Granted | Granted | Granted | Prohibited | Tenant root; soft-delete only |
| `organization_memberships` | Granted | Granted | Granted | Prohibited | Tenant binding; soft-delete only (`active = FALSE`) |
| `auth_sessions` | Granted | Granted | Granted | Granted | Permits session revocation and cascade cleanup |
| `refresh_tokens` | Granted | Granted | **Column-Level Only** | **Prohibited** | **`GRANT UPDATE (consumed_at, replaced_by_token_id)` only; partial unique index `uq_refresh_tokens_one_active_per_session` enforces $\le 1$ active token; guarded by triggers** |
| `products` | Granted | Granted | Granted | Granted | Scoped by `organization_id` |
| `product_categories` | Granted | Granted | Granted | Granted | Scoped by `organization_id` |
| `warehouses` | Granted | Granted | Granted | Granted | Scoped by `organization_id` |
| `inventory_items` | Granted | Granted | Granted | Prohibited | Monitored stock records; non-negative check |
| `inventory_ledger_entries` | Granted | Granted | **Prohibited** | **Prohibited** | **Append-only ledger; guarded by V2 trigger** |
| `orders` | Granted | Granted | Granted | Prohibited | State-machine transitions |
| `order_items` | Granted | Granted | Granted | Prohibited | Order reservation line items |
| `transfers` | Granted | Granted | Granted | Prohibited | Multi-warehouse transfer tracking |
| `transfer_items` | Granted | Granted | Granted | Prohibited | Transfer item lines |
| `audit_logs` | Granted | Granted | **Prohibited** | **Prohibited** | **Append-only security audit log; guarded by V2 trigger** |

### 3. Independent Verification Strategy (Privilege Layer vs. Trigger Layer)
PostgreSQL evaluates statement privileges before executing triggers. If `stockguard_app` attempts an unauthorized operation (`DELETE` or updating an immutable column), PostgreSQL rejects it at the privilege check stage with `SQLSTATE 42501` (`insufficient_privilege`), and the trigger never executes.
Therefore, STOCKGUARD's planned integration verification suite separates:
1. **Runtime Role Privilege Tests (`stockguard_app` connection):** Asserts `SQLSTATE 42501` on unauthorized table `DELETE` and immutable column `UPDATE`.
2. **Database Trigger & Constraint Tests (`stockguard_test_privileged` connection):** Connects with table privileges to directly test that triggers fire and reject violations with `SQLSTATE 55000` and `23505`.
