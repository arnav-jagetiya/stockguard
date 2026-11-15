# ADR-014: Observability, Prometheus Metrics & Three-Network Security Topology

| Field | Value |
|---|---|
| **Status** | Accepted (Final Three-Network Architecture Alignment) |
| **Date** | 2026-11-14 |
| **Applies To** | Milestone 6 Phase 2 & Phase 3 |
| **Authoritative References** | [ADR-001](ADR-001-modular-monolith.md), [ADR-010](ADR-010-authentication-and-session-management.md), [ADR-011](ADR-011-authorization-and-organization-access-control.md), [Milestone 6 Implementation Plan §3.7](../milestone-6-implementation-plan.md), [Phase 2 Specification](../milestone-6-phase-2-specification.md) |

> [!IMPORTANT]
> This architectural decision record formalizes the Prometheus metrics architecture, low-cardinality tag governance, and three-network Docker deployment security topology for StockGuard's management endpoints.

---

## 1. Context

To monitor operational health, database throughput, background workers, and business performance across production environments, STOCKGUARD requires metric instrumentation.

However, exposing metric endpoints introduces severe security and operational risks if not architected with care:
1. **Security Exposure:** Prometheus endpoints (`/actuator/prometheus`) reveal internal architecture, JVM memory footprints, database connection pool saturation, cache hit ratios, and traffic patterns. Publicly exposing `/actuator/prometheus` facilitates reconnaissance for denial-of-service and exploit campaigns.
2. **Permission Confusion (Privilege Separation):** Reusing business permissions (such as `audit:view`) for scraping metrics is dangerous. `audit:view` grants access to sensitive human audit logs containing organizational user activity and membership changes. An automated Prometheus scraper must not possess credentials allowing it to query business audit logs.
3. **Database Network Isolation:** The metrics scraper agent must never possess network access to the PostgreSQL database container. Ingress to PostgreSQL must be strictly restricted to the StockGuard application container.
4. **Health Probe Availability:** Kubernetes, Docker health checks, and load balancers require unauthenticated, fast access to `/actuator/health/liveness` and `/actuator/health/readiness` for container lifecycle management. Hardening metrics must not accidentally block health probes.
5. **Metric Cardinality Explosion:** Adding unbounded identifiers (such as `userId`, `orderId`, `sku`, or `organizationId`) to Prometheus metric tags can cause a cardinality explosion. A tag with $100,000$ unique values creates $100,000$ distinct time series in memory, crashing Prometheus agents and bloating TSDB storage.

---

## 2. Decision

### 2.1 Three-Network Docker Deployment Security Topology

StockGuard defines a hardened three-network container topology for production Docker deployments:

```mermaid
flowchart TD
    subgraph ExternalIngress["Host & External Ingress"]
        CLIENT["Web Browser / Client API"]
        LB["Reverse Proxy / Load Balancer (Nginx / ALB)"]
    end

    subgraph DockerHost["Docker Host Environment"]
        subgraph NetPublic["Docker Network 1: stockguard-public"]
            APP_PUB["StockGuard App Container<br/>(Port 8080 Published: 8080:8080)"]
            LB_CONTAINER["Reverse Proxy Container"]
        end

        subgraph NetAppDb["Docker Network 2: stockguard-app-db (Private Backend)"]
            APP_DB["StockGuard App Container<br/>(JDBC Connection Pool)"]
            PG["PostgreSQL Database Container<br/>(Port 5432 - NO External Access)"]
        end

        subgraph NetMonitoring["Docker Network 3: stockguard-monitoring (Private Management)"]
            APP_MGMT["StockGuard App Container<br/>(Port 8081 Exposed - UNPUBLISHED)"]
            PROM["Prometheus Scraper Container<br/>(Internal Scraper Agent)"]
        end
    end

    CLIENT --> LB
    LB --> LB_CONTAINER
    LB_CONTAINER -->|HTTP Traffic on Port 8080| APP_PUB

    APP_DB -->|TCP 5432 (stockguard_app / stockguard_owner)| PG
    PROM -->|Internal Scrape on Port 8081<br/>HTTP Basic Auth (ROLE_MONITORING)| APP_MGMT
```

#### 1. Three Isolated Docker Networks:
- **Network 1: `stockguard-public` (Public Ingress Network):**
  - Members: Reverse proxy / load balancer container and the StockGuard application container.
  - Port 8080 is published to the host (`ports: ["8080:8080"]`).
  - Routes client authentication (`/api/auth/login`), business APIs (`/api/**`), and public health probes (`/actuator/health`, `/actuator/info`).
- **Network 2: `stockguard-app-db` (Private Application/Database Network):**
  - Members: StockGuard application container and PostgreSQL database container.
  - PostgreSQL port 5432 is accessible ONLY on this network.
  - **Prometheus is NOT a member of this network.** Prometheus has zero route or network visibility to the database.
- **Network 3: `stockguard-monitoring` (Private Monitoring Network):**
  - Members: StockGuard application container and Prometheus scraper container.
  - Dedicated management port 8081 (`management.server.port: 8081`) binds inside the container to `0.0.0.0:8081`.
  - **Port 8081 is NOT published to the host (`ports` directive omitted; declared under `expose: ["8081"]`).**
  - Completely unreachable from the public internet and external host interfaces.

#### 2. StockGuard App Multi-Homing:
The StockGuard application container is attached to all three networks:
1. Receives external API calls via `stockguard-public` on port 8080.
2. Interacts with PostgreSQL via `stockguard-app-db` on port 5432.
3. Serves metric scrape requests via `stockguard-monitoring` on port 8081.

#### 3. Scraper Authentication & Privilege Separation:
- Prometheus targets: `http://stockguard-app:8081/actuator/prometheus`.
- Scraping requires HTTP Basic Authentication:
  `STOCKGUARD_MONITORING_SCRAPER_USERNAME: stockguard-prometheus`
  `STOCKGUARD_MONITORING_SCRAPER_PASSWORD: ${PROMETHEUS_SCRAPER_SECRET}`
- Scraper identity is assigned the dedicated system authority `ROLE_MONITORING`.
- **Privilege Separation:**
  - The `stockguard-prometheus` identity has NO presence in `users` or `organization_memberships`. It has no JWT, no organization context, and cannot access `/api/**`.
  - Business JWT tokens (including `ADMIN` users) lack `ROLE_MONITORING` and cannot access `/actuator/prometheus`.

#### 4. Preserved Health Probes Accessibility:
- Public health probes (`/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info`) remain configured as `.permitAll()` on port 8080.
- Docker health checks query `http://localhost:8080/actuator/health/liveness` without credentials.

#### 5. Local Development vs. Production:

| Configuration Aspect | Local Development (`dev` Profile) | Production (`prod` Profile) |
|---|---|---|
| Management Port | Single port `8080` (colocated with app). | Dedicated internal port `8081`. |
| Port Publication | `8080` published on localhost. | Only `8080` published; `8081` private to `stockguard-monitoring`. |
| Scraper Authentication | Permitted on localhost or uses default dev secret (`dev-scrape-pass`). | Strict HTTP Basic Auth with strong randomized secret (`PROMETHEUS_SCRAPER_SECRET`). |
| Database Isolation | Single Docker bridge for local dev. | Three strictly isolated networks (`stockguard-public`, `stockguard-app-db`, `stockguard-monitoring`). |

---

### 2.2 Low-Cardinality Tag Governance

Every Micrometer metric registered in StockGuard must strictly adhere to the **Low-Cardinality Tagging Invariant**:

> [!CAUTION]
> **Cardinality Invariant:**
> Metric tags must NEVER contain unboundedly growing values, including UUIDs (`organizationId`, `userId`, `productId`, `warehouseId`, `orderId`), email addresses, IP addresses, trace IDs, or user-supplied strings.

#### Permitted Tag Values:
Tags are strictly restricted to finite, well-known, compile-time enums or bounded status categories:
- `status`: `SUCCESS`, `FAILURE`, `OPEN`, `RESOLVED`, `REJECTED`, `EXPIRED`
- `operation_type`: `RECEIVE`, `ADJUST`, `TRANSFER`, `RESERVE`, `FULFILL`, `CANCEL`
- `discrepancy_type`: `DELTA_MISMATCH`, `MISSING_BALANCE_ROW`, `MISSING_LEDGER_ENTRIES`
- `reason`: `bad_credentials`, `token_expired`, `replay_detected`, `lock_contention`

---

### 2.3 Core Standardized Metrics Specification

| Metric Name | Type | Permitted Tags | Description |
|---|---|---|---|
| `stockguard.reconciliation.executions.total` | Counter | `status={"SUCCESS", "FAILURE"}` | Total reconciliation sweeps executed. |
| `stockguard.reconciliation.duration.seconds` | Timer | `status={"SUCCESS", "FAILURE"}` | Execution duration distribution of reconciliation sweeps. |
| `stockguard.reconciliation.org.executions.total` | Counter | `status={"SUCCESS", "FAILURE"}` | Per-organization reconciliation execution count. |
| `stockguard.reconciliation.org.failures.total` | Counter | *(none)* | Total organization-level reconciliation failures. |
| `stockguard.reconciliation.lock.skipped.total` | Counter | *(none)* | Total sweeps skipped due to advisory lock contention. |
| `stockguard.reconciliation.discrepancies.detected` | Counter | `discrepancy_type` | Total discrepancies identified during reconciliation sweeps. |
| `stockguard.reconciliation.discrepancies.active` | Gauge | `status={"OPEN", "ACKNOWLEDGED", "INVESTIGATING"}` | Current count of unresolved discrepancies across all tenants. |
| `stockguard.reservations.created.total` | Counter | *(none)* | Total stock reservations successfully created. |
| `stockguard.reservations.expired.total` | Counter | `status={"SUCCESS", "FAILED"}` | Total reservations cancelled by the expiration worker. |
| `stockguard.reservations.released.total` | Counter | *(none)* | Total reservations explicitly released by operators. |
| `stockguard.reservations.consumed.total` | Counter | *(none)* | Total reservations converted into fulfilled orders. |
| `stockguard.inventory.operations.total` | Counter | `operation_type`, `status` | Total inventory transactions processed. |
| `stockguard.auth.failures.total` | Counter | `reason={"bad_credentials", "token_expired", "replay_detected"}` | Total unauthenticated/failed authentication attempts. |
| `stockguard.db.deadlock.retries.total` | Counter | *(none)* | Total database transaction retries triggered by PostgreSQL deadlocks or serialization failures. |

---

## 3. Consequences

### Positive
- **Physical Network Isolation:** Three-network design ensures Prometheus has zero access to PostgreSQL, and port 8081 is unreachable from outside the Docker host.
- **Dedicated Scraper Identity:** Machine-to-machine authentication decouples scraper credentials from human business permissions.
- **Uncompromised Health Probes:** Public probes remain open and fast on port 8080 for load balancers.
- **Stable TSDB Storage:** Low-cardinality tags guarantee predictable metric storage without memory blowups.

### Negative / Trade-offs
- **Multi-Network Compose Declarations:** Requires declaring three bridge networks in `docker-compose.yml` for multi-container deployments.

---

## 4. Acceptance Criteria

1. In production, `/actuator/prometheus` is bound to port 8081 on private network `stockguard-monitoring`, unpublished to the host.
2. PostgreSQL resides exclusively on `stockguard-app-db`; Prometheus container has no network route to PostgreSQL.
3. Scraper authenticates via HTTP Basic Auth with dedicated `ROLE_MONITORING` identity.
4. `/actuator/health` and sub-probes remain `.permitAll()` on port 8080.
5. Business users and JWT tokens cannot access `/actuator/prometheus`.
6. All custom metrics enforce low-cardinality tags with zero UUIDs.
