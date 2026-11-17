# STOCKGUARD — Production Docker Deployment Runbook

**Document Version:** 1.0.0  
**Status:** Authoritative  

---

## 1. Prerequisites

Before deploying STOCKGUARD to production infrastructure, ensure the host environment meets the following specifications:

- **Operating System:** Linux (Ubuntu 22.04 LTS / Debian 12 / RHEL 9 recommended).
- **Compute Resources:** Minimum 2 vCPUs, 4 GB RAM, 20 GB SSD storage.
- **Software Dependencies:**
  - Docker Engine >= 24.0
  - Docker Compose Plugin >= 2.20
  - OpenSSL CLI (for certificate and secret generation)
  - `curl` / `wget`

---

## 2. Step-by-Step Production Deployment

### Step 1: Clone Repository & Create Production Environment File
```bash
git clone https://github.com/stockguard/stockguard.git /opt/stockguard
cd /opt/stockguard

# Copy production configuration template
cp .env.example .env
chmod 600 .env
```

### Step 2: Populate Production Secrets in `.env`
Edit `.env` using your chosen secure editor. Replace all placeholders (`replace-with-...`) with cryptographically generated values:
```bash
# Set secure permissions
chmod 600 .env
```

### Step 3: Configure TLS Certificates
Mount your commercial or Let's Encrypt TLS certificates into `nginx/ssl/`:
```bash
mkdir -p nginx/ssl
cp /etc/letsencrypt/live/app.stockguard.com/fullchain.pem nginx/ssl/cert.pem
cp /etc/letsencrypt/live/app.stockguard.com/privkey.pem nginx/ssl/key.pem
chmod 600 nginx/ssl/key.pem
chmod 644 nginx/ssl/cert.pem
```
*(If no certificates are provided, Nginx automatically generates a temporary fallback self-signed certificate upon boot).*

### Step 4: Build and Launch Containers
```bash
# Validate Compose configuration syntax
docker compose -f docker-compose.prod.yml config

# Build and start services in detached mode
docker compose -f docker-compose.prod.yml up -d --build
```

### Step 5: Verify Container Health & Initialization
```bash
# Check status and health probes
docker compose -f docker-compose.prod.yml ps

# Inspect backend startup and Flyway migration execution
docker compose -f docker-compose.prod.yml logs -f stockguard-app
```

Expected output confirms:
- PostgreSQL initialized and healthy.
- Flyway applied all database migrations up to version `V11`.
- Hibernate schema validation succeeded (`ddl-auto: validate`).
- Spring Boot started Tomcat on port `8080` (core) and `8081` (management).
- Nginx healthy on ports `80` and `443`.

---

## 3. Initial Administrator Provisioning

For a brand new installation with an empty database:

1. In `.env`, temporarily set:
   ```env
   STOCKGUARD_BOOTSTRAP_ADMIN_ENABLED=true
   STOCKGUARD_BOOTSTRAP_ADMIN_EMAIL=admin@yourcompany.com
   STOCKGUARD_BOOTSTRAP_ADMIN_PASSWORD=YourSecureAdminPassword123!
   STOCKGUARD_BOOTSTRAP_ADMIN_FULL_NAME="System Administrator"
   STOCKGUARD_BOOTSTRAP_ORG_NAME="Primary Organization"
   ```
2. Restart backend:
   ```bash
   docker compose -f docker-compose.prod.yml up -d stockguard-app
   ```
3. Once the administrator is initialized, disable bootstrap in `.env`:
   ```env
   STOCKGUARD_BOOTSTRAP_ADMIN_ENABLED=false
   ```
4. Reload backend to prevent subsequent bootstrap invocations:
   ```bash
   docker compose -f docker-compose.prod.yml up -d stockguard-app
   ```

---

## 4. Maintenance & Zero-Downtime Updates

### Graceful Redeployment / Upgrade Procedure
```bash
# 1. Pull latest verified release tag
git fetch --tags
git checkout tags/v1.0.0

# 2. Build updated images
docker compose -f docker-compose.prod.yml build

# 3. Perform rolling reload
docker compose -f docker-compose.prod.yml up -d --no-deps --build stockguard-app
docker compose -f docker-compose.prod.yml up -d --no-deps --build nginx

# 4. Prune unused dangling images
docker image prune -f
```
