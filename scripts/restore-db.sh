#!/usr/bin/env bash
# ==============================================================================
# STOCKGUARD — PostgreSQL Database Restore Script (ADR-002 / Milestone 9B)
# ==============================================================================
# Restores a custom-format dump (pg_dump -Fc) into a designated target database.
# Supports isolated testing and non-destructive recovery verification.
# ==============================================================================

set -euo pipefail

if [ "$#" -lt 1 ]; then
    echo "Usage: $0 <backup-file.dump> [target-database-name]"
    echo "Example: $0 ./backups/stockguard_backup_20261117_120000.dump stockguard_restore_test"
    exit 1
fi

BACKUP_FILE="$1"
CONTAINER_NAME="${PG_CONTAINER_NAME:-stockguard-postgres}"
TARGET_DB="${2:-${POSTGRES_DB:-stockguard}}"
DB_USER="${POSTGRES_USER:-stockguard}"

if [ ! -f "${BACKUP_FILE}" ]; then
    echo "ERROR: Backup file does not exist: ${BACKUP_FILE}" >&2
    exit 1
fi

echo "[$(date -u +"%Y-%m-%dT%H:%M:%SZ")] Initiating StockGuard database restore..."
echo "  Backup Source   : ${BACKUP_FILE}"
echo "  Target Database : ${TARGET_DB}"
echo "  Container       : ${CONTAINER_NAME}"

# Ensure target database exists in PostgreSQL
echo "  Verifying existence of target database '${TARGET_DB}'..."
DB_EXISTS=$(docker exec "${CONTAINER_NAME}" psql -U "${DB_USER}" -tAc "SELECT 1 FROM pg_database WHERE datname='${TARGET_DB}'" || true)

if [ "${DB_EXISTS}" != "1" ]; then
    echo "  Creating target database '${TARGET_DB}'..."
    docker exec "${CONTAINER_NAME}" createdb -U "${DB_USER}" "${TARGET_DB}"
    docker exec "${CONTAINER_NAME}" psql -U "${DB_USER}" -d "${TARGET_DB}" -c "GRANT ALL ON SCHEMA public TO stockguard_owner; GRANT ALL ON SCHEMA public TO stockguard; GRANT ALL ON SCHEMA public TO stockguard_app;"
fi

# Restore dump into target database
echo "  Executing pg_restore into '${TARGET_DB}'..."
cat "${BACKUP_FILE}" | docker exec -i "${CONTAINER_NAME}" pg_restore -U "${DB_USER}" -d "${TARGET_DB}" --no-owner

echo "[$(date -u +"%Y-%m-%dT%H:%M:%SZ")] Database restore completed successfully for database '${TARGET_DB}'."
