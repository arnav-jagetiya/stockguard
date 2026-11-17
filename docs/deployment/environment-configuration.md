# STOCKGUARD — Environment Configuration Specification

**Document Version:** 1.0.0  
**Status:** Authoritative  
**Target Profile:** `prod` (Spring Boot production profile)  

---

## 1. Production Secrets & Configuration Matrix

In production (`SPRING_PROFILES_ACTIVE=prod`), STOCKGUARD enforces strict fail-fast validation. The application will immediately abort startup if required secrets or database connection strings are absent.

| Environment Variable | Required in Prod? | Default Value | Format / Constraints | Description |
| :--- | :---: | :--- | :--- | :--- |
| `SPRING_PROFILES_ACTIVE` | **Yes** | `prod` | String (`prod`) | Activates production profile with hardened security defaults. |
| `POSTGRES_DB` | **Yes** | `stockguard` | String | Database name initialized in PostgreSQL. |
| `POSTGRES_PASSWORD` | **Yes** | *None* | Secure String (> 16 chars) | PostgreSQL superuser/admin initialization password. |
| `APP_DB_USER` | **Yes** | `stockguard_app` | String | Least-privilege runtime DML application role. |
| `APP_DB_PASSWORD` | **Yes** | *None* | Secure String (> 16 chars) | Password for `stockguard_app`. |
| `OWNER_DB_USER` | **Yes** | `stockguard_owner` | String | Migration owner role executing Flyway migrations. |
| `OWNER_DB_PASSWORD` | **Yes** | *None* | Secure String (> 16 chars) | Password for `stockguard_owner`. |
| `SPRING_DATASOURCE_URL` | **Yes** | `jdbc:postgresql://postgres:5432/stockguard?sslmode=disable` | JDBC URI | Database URL used by runtime HikariCP pool. |
| `FLYWAY_URL` | **Yes** | `jdbc:postgresql://postgres:5432/stockguard?sslmode=disable` | JDBC URI | Database URL used by Flyway migration engine. |
| `STOCKGUARD_JWT_ACTIVE_SECRET` | **Yes** | *None (Fail-Fast)* | Base64 String (>= 256 bits) | Cryptographic signing secret for HMAC-SHA256 access tokens. |
| `STOCKGUARD_JWT_ACTIVE_KEY_ID` | No | `sg-prod-2026-v1` | String | Key ID for active signing key rotation. |
| `STOCKGUARD_CORS_ALLOWED_ORIGINS` | **Yes** | `https://app.stockguard.com` | Comma-delimited URLs | Permitted frontend origins. Wildcards (`*`) strictly disallowed. |
| `STOCKGUARD_TRUSTED_PROXIES` | **Yes** | `172.28.0.0/16,127.0.0.1` | CIDRs / IPs | Reverse proxy subnets for rate-limiting client IP resolution. |
| `PROMETHEUS_SCRAPER_SECRET` | **Yes** | *None (Fail-Fast)* | Secure String | HTTP Basic auth password for `/actuator/prometheus`. Injected via Docker Compose secret and mounted at `/etc/prometheus/scraper_password`. |
| `STOCKGUARD_REGISTRATION_ENABLED` | No | `false` | Boolean (`false`) | Public organization registration. Must be `false` in production. |
| `STOCKGUARD_BOOTSTRAP_ADMIN_ENABLED` | No | `false` | Boolean (`false`) | One-time bootstrap administrator provisioning. |
| `STOCKGUARD_DEMO_SEED_ENABLED` | No | `false` | Boolean (`false`) | Synthetic demo data seeder. Strictly disabled in prod. |
| `STOCKGUARD_PUBLIC_DEMO_ENABLED` | No | `false` | Boolean (`false`) | Enables public read-only demo access (`POST /api/auth/demo-login`) and visitor provisioning. |
| `STOCKGUARD_PUBLIC_DEMO_EMAIL` | No | `demo.visitor@stockguard.dev` | Email String | Email identifier for the public demo visitor identity. |
| `STOCKGUARD_PUBLIC_DEMO_ORG_NAME` | No | `Acme Industrial Supply Pvt. Ltd.` | String | Organization target for the public demo visitor session. |
| `STOCKGUARD_RATE_LIMIT_DEMO_LOGIN` | No | `10` | Integer (> 0) | Rate limit in requests per minute per IP for `/api/auth/demo-login`. |

---

## 2. Generating Cryptographic Secrets

Use OpenSSL on a secure machine to generate all required passwords and JWT keys before deploying:

```bash
# 1. Generate 256-bit Base64 JWT Active Secret (Minimum 32 bytes)
openssl rand -base64 64

# 2. Generate Random Database Passwords
openssl rand -base64 32

# 3. Generate Prometheus Scraper Secret
openssl rand -base64 32
```

---

## 3. Profiles Comparison Matrix

| Property / Feature | Development (`dev`) | Production (`prod`) | Testing (`test`) |
| :--- | :--- | :--- | :--- |
| Default DB Port | `5433` (Host) | Internal Docker `5432` | Testcontainers Dynamic |
| DDL Auto | `validate` | `validate` | `validate` |
| Show SQL | `false` | `false` | `false` |
| Management Port | `8080` (Embedded) | `8081` (Isolated) | Dynamic |
| Health Endpoint Details | `when-authorized` | `never` | `always` |
| Demo Credentials Endpoint | Accessible on Loopback | **404 Not Found** | Disabled |
| Demo Seeder Runner | Optional (Acme Industrial) | **Disabled** | Disabled |
| Cookie Secure Flag | `false` (HTTP) | `true` (HTTPS only) | `false` |
| Registration Enabled | Configurable | `false` by default | `true` |
| Public Demo Mode | `true` by default | `false` (Opt-In) | Disabled |
| Public CORS | `http://localhost:5173` | Strict Domain Only | `*` |
