# STOCKGUARD — Production Security Checklist & Hardening Verification

**Document Version:** 1.0.0  
**Status:** Mandatory Pre-Deployment Gate  

---

## 1. Pre-Deployment Verification Checklist

Every item in this checklist must be verified before declaring production readiness:

- [x] **Network Isolation:** Database port `5432` has NO published host port and is confined to `stockguard-app-db` (`internal: true`).
- [x] **Management Isolation:** Actuator port `8081` is confined to `stockguard-monitoring` (`internal: true`) and inaccessible from the public internet.
- [x] **Reverse Proxy Actuator Blocking:** Nginx strictly blocks `/actuator` and returns `404 Not Found`.
- [x] **Least Privilege Roles:** Runtime application connects via `stockguard_app` (DML only); migrations run via `stockguard_owner` (DDL).
- [x] **Fail-Fast Secrets:** No default development passwords exist in `application-prod.yml`. Application refuses to start if `STOCKGUARD_JWT_ACTIVE_SECRET` or database credentials are missing.
- [x] **Public Registration Disabled:** `STOCKGUARD_REGISTRATION_ENABLED=false` is enforced in production.
- [x] **Demo Endpoint Inaccessible:** `GET /api/auth/demo-credentials` returns `404 Not Found` in production profile.
- [x] **Demo Seeder Disabled:** `STOCKGUARD_DEMO_SEED_ENABLED=false` is enforced in production profile.
- [x] **Secure Cookie Flags:** Refresh token cookie has `HttpOnly=true`, `Secure=true`, `SameSite=Strict`, `Path=/api/auth`.
- [x] **Token Family Reuse Detection:** Refresh token reuse detection invalidates the entire session family and logs a security event.
- [x] **Rate Limiting Active:** Rate limiting is enforced on `/api/auth/login` (5/min), `/api/auth/refresh` (20/min), and `/api/auth/demo-login` (10/min).
- [x] **Public Demo Mode Safeguards:** Controlled via `STOCKGUARD_PUBLIC_DEMO_ENABLED` (default false in prod); demo visitor has an unguessable BCrypt password hash; server-side `DemoModeMutationGuardFilter` blocks all mutation HTTP methods (`POST`, `PUT`, `PATCH`, `DELETE`) with `DEMO_MODE_MUTATION_PROHIBITED`.
- [x] **Prometheus Scraper Authentication:** Injected securely via Docker Compose secret and read from `/etc/prometheus/scraper_password` (`password_file`), avoiding unexpanded YAML variables.
- [x] **Database Restore Script Safety:** `scripts/restore-db.sh` and `scripts/restore-db.ps1` require a mandatory database parameter with strict regex validation, and block accidental overwrites of existing production databases without explicit `--force` / `-Force` confirmation.
- [x] **Container Non-Root Execution:** Backend runs as unprivileged user `stockguard` (UID 10001); Nginx runs as `nginx`.
- [x] **Security Headers Active:** HSTS, CSP, X-Frame-Options: DENY, X-Content-Type-Options: nosniff, Referrer-Policy, and Permissions-Policy are returned on all responses.
- [x] **Immutable Ledger Triggers:** PostgreSQL database triggers prohibit UPDATE, DELETE, or TRUNCATE on `inventory_ledger_entries` and `audit_logs`.

---

## 2. OWASP Top 10 Mitigations in StockGuard

| OWASP Vulnerability | STOCKGUARD Defensive Measure |
| :--- | :--- |
| **A01: Broken Access Control** | Organization-scoped multi-tenancy enforced at JPA repository and database query levels; RBAC permissions evaluated on every endpoint; `/actuator` blocked at reverse proxy. |
| **A02: Cryptographic Failures** | TLS 1.2/1.3 only; HMAC-SHA256 JWT signatures with >= 256-bit secrets; BCrypt password hashing (cost 12); SHA-256 peppered refresh token hashing. |
| **A03: Injection** | 100% parameterized SQL via Spring Data JPA and NamedParameterJdbcTemplate; Hibernate validation annotations; strict DDL validation. |
| **A04: Insecure Design** | Double-entry transaction ledger with append-only database triggers; atomic inventory state transitions; strict MVCC reconciliation engine. |
| **A05: Security Misconfiguration** | Minimal Alpine container base images; separated dev/prod YAML profiles; fail-fast validation for all credentials; disabled debug/stack traces. |
| **A06: Vulnerable & Outdated Components** | Automated CI scanning; minimal direct dependencies; modern LTS runtimes (Java 21, Node 22). |
| **A07: Identification & Auth Failures** | Sliding-window token rotation with reuse detection; brute-force rate-limiting; short access token TTL (15 min). |
| **A08: Software & Data Integrity Failures** | Docker images built from multi-stage definitions; strict CI validation before release. |
| **A09: Security Logging & Monitoring** | Immutable append-only `audit_logs` table; structured security audit logging for authentication events; Prometheus SLI alerts. |
| **A10: Server-Side Request Forgery (SSRF)** | Zero external URL fetching by backend APIs; strict loopback origin checks on dev utilities. |

---

## 3. Secret Rotation Procedure

### JWT Signing Key Rotation (Zero-Downtime)
1. Generate new 256-bit secret: `openssl rand -base64 64`.
2. Move current `active-key` into `retiring-keys` map in `application-prod.yml`.
3. Set newly generated secret as `active-key` with a new key ID (`sg-prod-2026-v2`).
4. Existing user tokens will continue validating against the retiring key until expiration (15 minutes).
5. All newly minted tokens use the new active key immediately.
6. After 15 minutes, remove the retiring key from configuration.
