# STOCKGUARD — Domain Configuration & TLS/HTTPS Provisioning

**Document Version:** 1.0.0  
**Status:** Authoritative  

---

## 1. DNS Record Configuration

Point your public domain name to the production server's public IPv4 address:

| Type | Name / Host | Target Value | TTL |
| :--- | :--- | :--- | :--- |
| `A` | `app.stockguard.com` | `203.0.113.10` (Your Server Public IP) | `300` (5 mins) |
| `A` | `stockguard.com` | `203.0.113.10` | `300` |

Verify DNS propagation before provisioning certificates:
```bash
dig +short app.stockguard.com
```

---

## 2. Let's Encrypt / Certbot Automated Provisioning

STOCKGUARD's Nginx configuration includes built-in support for Certbot HTTP-01 webroot verification via `location /.well-known/acme-challenge/`.

### Initial Certificate Issuance
1. Install Certbot on the host machine:
   ```bash
   sudo apt-get update
   sudo apt-get install -y certbot
   ```
2. Run standalone or webroot ACME issuance:
   ```bash
   sudo certbot certonly --webroot -w /var/www/certbot \
       -d app.stockguard.com \
       --agree-tos \
       -m devops@stockguard.com \
       --no-eff-email
   ```
3. Copy or link certificates into `nginx/ssl/`:
   ```bash
   mkdir -p /opt/stockguard/nginx/ssl
   sudo cp /etc/letsencrypt/live/app.stockguard.com/fullchain.pem /opt/stockguard/nginx/ssl/cert.pem
   sudo cp /etc/letsencrypt/live/app.stockguard.com/privkey.pem /opt/stockguard/nginx/ssl/key.pem
   sudo chmod 644 /opt/stockguard/nginx/ssl/cert.pem
   sudo chmod 600 /opt/stockguard/nginx/ssl/key.pem
   ```
4. Reload Nginx:
   ```bash
   docker compose -f /opt/stockguard/docker-compose.prod.yml exec nginx nginx -s reload
   ```

### Automated Certificate Renewal Cron Job
Add the following renewal cron entry to `/etc/cron.d/certbot-stockguard`:
```cron
0 3 * * * root certbot renew --quiet --deploy-hook "cp /etc/letsencrypt/live/app.stockguard.com/fullchain.pem /opt/stockguard/nginx/ssl/cert.pem && cp /etc/letsencrypt/live/app.stockguard.com/privkey.pem /opt/stockguard/nginx/ssl/key.pem && docker compose -f /opt/stockguard/docker-compose.prod.yml exec nginx nginx -s reload"
```

---

## 3. Commercial TLS Certificate Installation

If utilizing custom or wildcard enterprise certificates from a Certificate Authority (DigiCert, Sectigo, etc.):

1. Combine the server certificate and intermediate CA bundle into `cert.pem`:
   ```bash
   cat server_cert.crt intermediate_bundle.crt > /opt/stockguard/nginx/ssl/cert.pem
   ```
2. Place the private key in `key.pem`:
   ```bash
   cp server_private.key /opt/stockguard/nginx/ssl/key.pem
   chmod 600 /opt/stockguard/nginx/ssl/key.pem
   ```
3. Restart or reload Nginx:
   ```bash
   docker compose -f /opt/stockguard/docker-compose.prod.yml restart nginx
   ```

---

## 4. Cryptographic Security Standards

STOCKGUARD's Nginx configuration enforces:
- **Protocols:** TLSv1.2, TLSv1.3 only (SSLv3, TLSv1.0, TLSv1.1 strictly disabled).
- **HSTS:** `max-age=31536000; includeSubDomains; preload` (enforces HTTPS browser-wide for 1 year).
- **Session Caching:** Shared 10MB SSL cache across worker processes with session tickets disabled.
