#!/bin/sh
set -e

SSL_DIR="/etc/nginx/ssl"
CERT_FILE="${SSL_DIR}/cert.pem"
KEY_FILE="${SSL_DIR}/key.pem"

mkdir -p "${SSL_DIR}"

# If certificate or private key does not exist, generate a self-signed fallback certificate
if [ ! -f "${CERT_FILE}" ] || [ ! -f "${KEY_FILE}" ]; then
    echo "Notice: SSL certificate or key not found in ${SSL_DIR}. Generating fallback self-signed TLS certificate..."
    openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
        -keyout "${KEY_FILE}" \
        -out "${CERT_FILE}" \
        -subj "/C=US/ST=State/L=City/O=StockGuard/OU=Platform/CN=localhost" 2>/dev/null
    chmod 600 "${KEY_FILE}"
    chmod 644 "${CERT_FILE}"
    echo "Fallback TLS certificate successfully generated at ${CERT_FILE}."
fi

# Execute the primary container command (typically nginx -g 'daemon off;')
exec "$@"
