#!/bin/sh
# ==============================================================================
# STOCKGUARD — Production Nginx Fail-Closed TLS Entrypoint
# ==============================================================================
# Validates that valid, matching TLS certificates are mounted read-only before
# launching Nginx. Fails closed immediately if certificates are missing, unreadable,
# corrupted, or mismatched. Never silently generates insecure self-signed certs.
# ==============================================================================

set -eu

SSL_DIR="/etc/nginx/ssl"
CERT_FILE="${SSL_DIR}/cert.pem"
KEY_FILE="${SSL_DIR}/key.pem"

# 1. Verify certificate and private key existence
if [ ! -f "${CERT_FILE}" ]; then
    echo "FATAL: Production TLS certificate file not found at ${CERT_FILE}." >&2
    echo "Provision valid TLS certificates or execute ./scripts/generate-dev-certs.sh for local testing." >&2
    exit 1
fi

if [ ! -f "${KEY_FILE}" ]; then
    echo "FATAL: Production TLS private key file not found at ${KEY_FILE}." >&2
    echo "Provision valid TLS private keys or execute ./scripts/generate-dev-certs.sh for local testing." >&2
    exit 1
fi

# 2. Verify file readability for Nginx process
if [ ! -r "${CERT_FILE}" ]; then
    echo "FATAL: Production TLS certificate at ${CERT_FILE} is not readable." >&2
    exit 1
fi

if [ ! -r "${KEY_FILE}" ]; then
    echo "FATAL: Production TLS private key at ${KEY_FILE} is not readable." >&2
    exit 1
fi

# 3. Cryptographically validate matching public keys (RSA / ECDSA / Ed25519)
CERT_PUBKEY=$(openssl x509 -noout -pubkey -in "${CERT_FILE}" 2>/dev/null || true)
if [ -z "${CERT_PUBKEY}" ]; then
    echo "FATAL: Failed to parse TLS certificate at ${CERT_FILE}. File may be corrupted or invalid format." >&2
    exit 1
fi

KEY_PUBKEY=$(openssl pkey -pubout -in "${KEY_FILE}" 2>/dev/null || true)
if [ -z "${KEY_PUBKEY}" ]; then
    echo "FATAL: Failed to parse TLS private key at ${KEY_FILE}. File may be corrupted or passphrase-protected." >&2
    exit 1
fi

if [ "${CERT_PUBKEY}" != "${KEY_PUBKEY}" ]; then
    echo "FATAL: Cryptographic mismatch: TLS certificate at ${CERT_FILE} does not match private key at ${KEY_FILE}." >&2
    exit 1
fi

echo "StockGuard Nginx: TLS certificate and matching private key verified successfully."

# Execute the primary container command (nginx -g 'daemon off;')
exec "$@"
