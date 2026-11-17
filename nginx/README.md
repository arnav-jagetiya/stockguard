# STOCKGUARD Nginx Reverse Proxy & TLS Configuration

## Architecture & Configuration Location

In the STOCKGUARD production architecture, the frontend SPA and Nginx reverse proxy are packaged together into a unified, hardened Alpine container via `frontend/Dockerfile`.

* **Canonical Server Configuration**: [`frontend/nginx.conf`](../frontend/nginx.conf)
* **Canonical Entrypoint Script**: [`frontend/entrypoint.sh`](../frontend/entrypoint.sh)

This setup ensures that all Nginx configuration files reside directly within the Docker build context (`./frontend`), eliminating path traversal and configuration duplication.

## SSL / TLS Certificates

The `nginx/ssl/` directory serves as the host mount point for production TLS certificates as specified in `docker-compose.prod.yml`:

```yaml
volumes:
  - ./nginx/ssl:/etc/nginx/ssl:ro
```

When deploying with Let's Encrypt or commercial certificates, place the certificate and private key files here:
- `nginx/ssl/cert.pem` (full certificate chain, permissions `0644`)
- `nginx/ssl/key.pem` (private key, permissions `0600`)

If no certificates are mounted, the container entrypoint (`frontend/entrypoint.sh`) generates a temporary self-signed certificate on boot to ensure HTTPS connectivity remains functional.
