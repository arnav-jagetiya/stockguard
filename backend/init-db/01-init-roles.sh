#!/usr/bin/env bash
# ==============================================================================
# STOCKGUARD — Secure Dual-Role Database Initialization Script (ADR-002)
# ==============================================================================
# Executed by official postgres:16-alpine entrypoint during initial cluster bootstrap.
#
# IMPORTANT OPERATIONAL NOTE:
# This script executes ONLY when the PostgreSQL data directory is initialized for
# the first time (fresh volume). If an existing data directory volume is mounted,
# /docker-entrypoint-initdb.d/ scripts are skipped entirely by PostgreSQL.
# In-place credential rotation for existing clusters must be performed via explicit
# administrative SQL (ALTER ROLE) commands, not by re-running initialization scripts.
# ==============================================================================

set -euo pipefail

# Role and Database Configuration with Production Defaults
DB_NAME="${POSTGRES_DB:-stockguard}"
ADMIN_USER="${POSTGRES_USER:-postgres}"
OWNER_USER="${OWNER_DB_USER:-stockguard_owner}"
OWNER_PASS="${OWNER_DB_PASSWORD:-}"
APP_USER="${APP_DB_USER:-stockguard_app}"
APP_PASS="${APP_DB_PASSWORD:-}"

# 1. Enforce Mandatory Password Configuration (Fail-Closed)
if [ -z "${OWNER_PASS}" ]; then
    echo "FATAL: OWNER_DB_PASSWORD environment variable is unset or empty." >&2
    exit 1
fi

if [ -z "${APP_PASS}" ]; then
    echo "FATAL: APP_DB_PASSWORD environment variable is unset or empty." >&2
    exit 1
fi

# 2. Validate SQL Identifier Syntax (alphanumeric and underscore only, starting with alpha/underscore)
validate_identifier() {
    local identifier="$1"
    local name="$2"
    if ! [[ "${identifier}" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]]; then
        echo "FATAL: Configured SQL identifier '${name}' contains invalid characters: '${identifier}'" >&2
        exit 1
    fi
}

validate_identifier "${DB_NAME}" "POSTGRES_DB"
validate_identifier "${ADMIN_USER}" "POSTGRES_USER"
validate_identifier "${OWNER_USER}" "OWNER_DB_USER"
validate_identifier "${APP_USER}" "APP_DB_USER"

# 3. Enforce Strict Role Separation (ADR-002)
# Migration owner (DDL) and runtime application (DML) roles must be distinct.
if [ "${OWNER_USER}" = "${APP_USER}" ]; then
    echo "FATAL: OWNER_DB_USER ('${OWNER_USER}') and APP_DB_USER ('${APP_USER}') cannot be identical. Distinct roles are required for least-privilege separation." >&2
    exit 1
fi

if [ "${OWNER_USER}" = "${ADMIN_USER}" ]; then
    echo "FATAL: OWNER_DB_USER ('${OWNER_USER}') cannot be identical to POSTGRES_USER ('${ADMIN_USER}')." >&2
    exit 1
fi

if [ "${APP_USER}" = "${ADMIN_USER}" ]; then
    echo "FATAL: APP_DB_USER ('${APP_USER}') cannot be identical to POSTGRES_USER ('${ADMIN_USER}')." >&2
    exit 1
fi

# Export resolved configuration for psql \getenv ingestion
export POSTGRES_DB="${DB_NAME}"
export POSTGRES_USER="${ADMIN_USER}"
export OWNER_DB_USER="${OWNER_USER}"
export OWNER_DB_PASSWORD="${OWNER_PASS}"
export APP_DB_USER="${APP_USER}"
export APP_DB_PASSWORD="${APP_PASS}"

echo "StockGuard: Initializing dual-role architecture for database '${DB_NAME}'..."
echo "  Migration Owner Role : ${OWNER_USER} (DDL / schema management)"
echo "  Application Role     : ${APP_USER} (DML runtime only)"

# 4. Execute Dual-Role Provisioning via Quoted Stdin Heredoc (ADR-002)
# - Credentials ingested via psql \getenv (never exposed in ps aux command-line arguments)
# - Quoted heredoc (<<'EOSQL') ensures zero Bash parameter expansion, command substitution, or backtick execution
# - Transaction-local configuration (is_local=true) explicitly managed within BEGIN...COMMIT transaction
# - Tagged procedural delimiter ($stockguard_init$) and format('%L', ...) eliminate dollar-quote delimiter collisions
# - \g /dev/null suppresses set_config return values, preventing credential leakage to stdout
psql -v ON_ERROR_STOP=1 --username "${ADMIN_USER}" --dbname "${DB_NAME}" <<'EOSQL'
\set QUIET 1
\getenv db_name POSTGRES_DB
\getenv admin_user POSTGRES_USER
\getenv owner_user OWNER_DB_USER
\getenv owner_pass OWNER_DB_PASSWORD
\getenv app_user APP_DB_USER
\getenv app_pass APP_DB_PASSWORD

BEGIN;

-- Transfer credentials into transaction-local configuration settings
-- \g /dev/null ensures set_config return values are not emitted to stdout
SELECT set_config('stockguard.owner_user', :'owner_user', true) \g /dev/null
SELECT set_config('stockguard.owner_pass', :'owner_pass', true) \g /dev/null
SELECT set_config('stockguard.app_user', :'app_user', true) \g /dev/null
SELECT set_config('stockguard.app_pass', :'app_pass', true) \g /dev/null

-- 1 & 2. Create or update roles with native identifier and literal escaping
DO $stockguard_init$
DECLARE
    v_owner_user text := current_setting('stockguard.owner_user');
    v_owner_pass text := current_setting('stockguard.owner_pass');
    v_app_user   text := current_setting('stockguard.app_user');
    v_app_pass   text := current_setting('stockguard.app_pass');
BEGIN
    -- Migration owner role (DDL / schema management)
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = v_owner_user) THEN
        EXECUTE format('CREATE ROLE %I WITH LOGIN PASSWORD %L', v_owner_user, v_owner_pass);
    ELSE
        EXECUTE format('ALTER ROLE %I WITH LOGIN PASSWORD %L', v_owner_user, v_owner_pass);
    END IF;

    -- Runtime application role (DML only, no DDL)
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = v_app_user) THEN
        EXECUTE format('CREATE ROLE %I WITH LOGIN PASSWORD %L', v_app_user, v_app_pass);
    ELSE
        EXECUTE format('ALTER ROLE %I WITH LOGIN PASSWORD %L', v_app_user, v_app_pass);
    END IF;
END $stockguard_init$;

-- 3. Grant database privileges to migration owner
GRANT ALL PRIVILEGES ON DATABASE :"db_name" TO :"owner_user";

-- 4. Grant schema privileges
GRANT ALL ON SCHEMA public TO :"owner_user";
GRANT ALL ON SCHEMA public TO :"admin_user";

-- 5. Grant runtime DML privileges to application role
GRANT USAGE ON SCHEMA public TO :"app_user";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"app_user";
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO :"app_user";

-- 6. Default privileges for future tables/sequences created by owner or admin
ALTER DEFAULT PRIVILEGES FOR ROLE :"owner_user" IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_user";

ALTER DEFAULT PRIVILEGES FOR ROLE :"owner_user" IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO :"app_user";

ALTER DEFAULT PRIVILEGES FOR ROLE :"admin_user" IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"app_user";

ALTER DEFAULT PRIVILEGES FOR ROLE :"admin_user" IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO :"app_user";

COMMIT;
EOSQL

echo "StockGuard: Dual-role provisioning completed successfully."
