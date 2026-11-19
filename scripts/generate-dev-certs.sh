#!/usr/bin/env bash
# ==============================================================================
# STOCKGUARD — Development Self-Signed TLS Certificate Generator
# ==============================================================================
# Generates local self-signed TLS certificates for development and local testing.
# NEVER use in production environments. Production requires valid CA-issued certs.
# ==============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
SSL_DIR="${REPO_ROOT}/nginx/ssl"
CERT_FILE="${SSL_DIR}/cert.pem"
KEY_FILE="${SSL_DIR}/key.pem"
DAYS_VALID=365
FORCE=false

for arg in "$@"; do
    case "$arg" in
        --force|-f)
            FORCE=true
            ;;
        --help|-h)
            echo "Usage: $0 [--force]"
            echo "Generates local self-signed TLS certificates in ${SSL_DIR}."
            exit 0
            ;;
        *)
            echo "Unknown argument: $arg" >&2
            exit 1
            ;;
    esac
done

if ! command -v openssl >/dev/null 2>&1; then
    echo "ERROR: 'openssl' command is required but not installed or not in PATH." >&2
    exit 1
fi

mkdir -p "${SSL_DIR}"

if [ -f "${CERT_FILE}" ] || [ -f "${KEY_FILE}" ]; then
    if [ "${FORCE}" != "true" ]; then
        echo "WARNING: Existing TLS certificates detected in ${SSL_DIR}:"
        [ -f "${CERT_FILE}" ] && echo "  - ${CERT_FILE}"
        [ -f "${KEY_FILE}" ] && echo "  - ${KEY_FILE}"
        echo "Refusing to overwrite without explicit confirmation. Pass --force to overwrite."
        exit 1
    else
        echo "Notice: Overwriting existing development TLS certificates (--force specified)."
    fi
fi

echo "Generating development self-signed TLS certificate (valid for ${DAYS_VALID} days)..."

# Temporary OpenSSL configuration for Subject Alternative Names (SAN)
OPENSSL_CONFIG=$(mktemp)
cat > "${OPENSSL_CONFIG}" <<EOF
[req]
default_bits = 2048
prompt = no
default_md = sha256
distinguished_name = dn
x509_extensions = v3_req

[dn]
C = US
ST = Development
L = Local
O = StockGuard Development
OU = Engineering
CN = localhost

[v3_req]
subjectAltName = @alt_names
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth

[alt_names]
DNS.1 = localhost
DNS.2 = *.localhost
DNS.3 = stockguard.local
DNS.4 = *.stockguard.local
IP.1 = 127.0.0.1
IP.2 = ::1
EOF

openssl req -x509 -nodes -days "${DAYS_VALID}" -newkey rsa:2048 \
    -keyout "${KEY_FILE}" \
    -out "${CERT_FILE}" \
    -config "${OPENSSL_CONFIG}" \
    2>/dev/null

rm -f "${OPENSSL_CONFIG}"

# Restrictive permissions
chmod 600 "${KEY_FILE}"
chmod 644 "${CERT_FILE}"

echo "================================================================================"
echo "DEVELOPMENT TLS CERTIFICATES SUCCESSFULLY GENERATED:"
echo "  Certificate : ${CERT_FILE}"
echo "  Private Key : ${KEY_FILE}"
echo "  Subject     : CN=localhost (StockGuard Development)"
echo "  SAN Entries : localhost, *.localhost, stockguard.local, 127.0.0.1, ::1"
echo "  Permissions : Key (0600), Cert (0644)"
echo "================================================================================"
echo "CAUTION: This certificate is strictly for local development and testing."
echo "Browsers will show a self-signed security warning until trusted."
echo "DO NOT USE THIS CERTIFICATE IN PRODUCTION."
