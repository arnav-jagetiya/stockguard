#!/usr/bin/env bash
# ==============================================================================
# STOCKGUARD — Automated PostgreSQL Database Backup Script (ADR-002 / Milestone 9B)
# ==============================================================================
# Performs a consistent, compressed custom-format backup using pg_dump -Fc.
# Safe for cron automation, container execution, and standalone host operations.
# ==============================================================================

set -euo pipefail

# Configuration with environment defaults
CONTAINER_NAME="${PG_CONTAINER_NAME:-stockguard-postgres}"
DB_NAME="${POSTGRES_DB:-stockguard}"
DB_USER="${POSTGRES_USER:-stockguard}"
BACKUP_DIR="${BACKUP_DIR:-./backups}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-7}"

TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
BACKUP_FILENAME="stockguard_backup_${TIMESTAMP}.dump"
TARGET_PATH="${BACKUP_DIR}/${BACKUP_FILENAME}"

mkdir -p "${BACKUP_DIR}"

echo "[$(date -u +"%Y-%m-%dT%H:%M:%SZ")] Starting StockGuard database backup..."
echo "  Target Database : ${DB_NAME}"
echo "  Container       : ${CONTAINER_NAME}"
echo "  Output File     : ${TARGET_PATH}"

# Execute pg_dump with custom archive format (-Fc enables compression and parallel restore)
if docker ps --format '{{.Names}}' | grep -Eq "^${CONTAINER_NAME}\$"; then
    echo "  Executing backup via running Docker container: ${CONTAINER_NAME}..."
    docker exec "${CONTAINER_NAME}" pg_dump -U "${DB_USER}" -d "${DB_NAME}" -Fc -v > "${TARGET_PATH}"
else
    echo "  Container ${CONTAINER_NAME} not found. Attempting direct host pg_dump..."
    PGPASSWORD="${POSTGRES_PASSWORD:-}" pg_dump -h "${PG_HOST:-localhost}" -p "${PG_PORT:-5432}" -U "${DB_USER}" -d "${DB_NAME}" -Fc -v > "${TARGET_PATH}"
fi

# Validate backup file existence and non-zero size
if [ ! -s "${TARGET_PATH}" ]; then
    echo "[$(date -u +"%Y-%m-%dT%H:%M:%SZ")] ERROR: Backup failed! Backup file is empty or missing: ${TARGET_PATH}" >&2
    exit 1
fi

FILE_SIZE=$(du -h "${TARGET_PATH}" | cut -f1)
echo "[$(date -u +"%Y-%m-%dT%H:%M:%SZ")] Backup completed successfully!"
echo "  Saved archive: ${TARGET_PATH} (${FILE_SIZE})"

# Prune archives older than RETENTION_DAYS
if [ "${RETENTION_DAYS}" -gt 0 ]; then
    echo "  Pruning backups older than ${RETENTION_DAYS} days in ${BACKUP_DIR}..."
    find "${BACKUP_DIR}" -name "stockguard_backup_*.dump" -type f -mtime "+${RETENTION_DAYS}" -delete || true
fi

echo "[$(date -u +"%Y-%m-%dT%H:%M:%SZ")] Backup workflow finished."
