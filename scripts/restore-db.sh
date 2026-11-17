#!/usr/bin/env bash
# ==============================================================================
# STOCKGUARD — PostgreSQL Database Restore Script (ADR-002 / Milestone 10 Hardened)
# ==============================================================================
# Restores a custom-format dump (pg_dump -Fc) into an explicitly designated target database.
# Enforces mandatory target database parameter, identifier regex validation,
# and confirmation protections for the active production database.
# ==============================================================================

set -euo pipefail

if [ "$#" -lt 2 ]; then
    echo "ERROR: Target database parameter is mandatory to prevent accidental overwrite of active data." >&2
    echo "Usage: $0 <backup-file.dump> <target-database-name> [--force]" >&2
    echo "Example (safe test restore): $0 ./backups/stockguard_backup_20261117_120000.dump stockguard_restore_test" >&2
    echo "Example (live database restore): $0 ./backups/stockguard_backup_20261117_120000.dump stockguard --force" >&2
    exit 1
fi

BACKUP_FILE="$1"
TARGET_DB="$2"
FORCE_FLAG="${3:-}"
CONTAINER_NAME="${PG_CONTAINER_NAME:-stockguard-postgres}"
DB_USER="${POSTGRES_USER:-stockguard}"
ACTIVE_DB="${POSTGRES_DB:-stockguard}"

# 1. Assert backup file exists
if [ ! -f "${BACKUP_FILE}" ]; then
    echo "ERROR: Backup file does not exist: ${BACKUP_FILE}" >&2
    exit 1
fi

# 2. Validate target database name syntax (alphanumeric and underscores only)
if [[ ! "${TARGET_DB}" =~ ^[a-zA-Z0-9_]+$ ]]; then
    echo "ERROR: Invalid target database name '${TARGET_DB}'. Only alphanumeric characters and underscores are permitted." >&2
    exit 1
fi

# 3. Active database overwrite protection
if [ "${TARGET_DB}" = "${ACTIVE_DB}" ]; then
    if [ "${FORCE_FLAG}" != "--force" ]; then
        if [ -t 0 ]; then
            echo "WARNING: Target database '${TARGET_DB}' matches active production database '${ACTIVE_DB}'!" >&2
            read -r -p "Type 'OVERWRITE' to proceed with restoring into the active database: " CONFIRMATION
            if [ "${CONFIRMATION}" != "OVERWRITE" ]; then
                echo "Restoration aborted by operator." >&2
                exit 1
            fi
        else
            echo "ERROR: Refusing to restore into active database '${ACTIVE_DB}' in non-interactive mode without '--force' flag." >&2
            exit 1
        fi
    else
        echo "WARNING: Restoring directly into active database '${ACTIVE_DB}' with explicit --force flag."
    fi
fi

echo "[$(date -u +"%Y-%m-%dT%H:%M:%SZ")] Initiating StockGuard database restore..."
echo "  Backup Source   : ${BACKUP_FILE}"
echo "  Target Database : ${TARGET_DB}"
echo "  Container       : ${CONTAINER_NAME}"

# 4. Verify existence of target database in PostgreSQL
echo "  Verifying existence of target database '${TARGET_DB}'..."
DB_EXISTS=$(docker exec "${CONTAINER_NAME}" psql -U "${DB_USER}" -tAc "SELECT 1 FROM pg_database WHERE datname='${TARGET_DB}'" || true)

if [ "${DB_EXISTS}" != "1" ]; then
    echo "  Creating target database '${TARGET_DB}'..."
    docker exec "${CONTAINER_NAME}" createdb -U "${DB_USER}" "${TARGET_DB}"
    docker exec "${CONTAINER_NAME}" psql -U "${DB_USER}" -d "${TARGET_DB}" -c "GRANT ALL ON SCHEMA public TO stockguard_owner; GRANT ALL ON SCHEMA public TO stockguard; GRANT ALL ON SCHEMA public TO stockguard_app;"
fi

# 5. Restore dump into target database
echo "  Executing pg_restore into '${TARGET_DB}'..."
cat "${BACKUP_FILE}" | docker exec -i "${CONTAINER_NAME}" pg_restore -U "${DB_USER}" -d "${TARGET_DB}" --no-owner

echo "[$(date -u +"%Y-%m-%dT%H:%M:%SZ")] Database restore completed successfully for database '${TARGET_DB}'."
