-- ============================================================================
-- STOCKGUARD — Testcontainers Pre-Migration Role Initialization Script
-- Executed by Testcontainers BEFORE Spring Boot and Flyway start.
-- Guarantees that stockguard_app exists prior to V3 execution so that
-- V3 Flyway migration grants execute natively and conditionally.
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stockguard_owner') THEN
        CREATE ROLE stockguard_owner WITH LOGIN PASSWORD 'test_owner_pass';
    ELSE
        ALTER ROLE stockguard_owner WITH LOGIN PASSWORD 'test_owner_pass';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stockguard_app') THEN
        CREATE ROLE stockguard_app WITH LOGIN PASSWORD 'test_app_pass'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    ELSE
        ALTER ROLE stockguard_app WITH LOGIN PASSWORD 'test_app_pass'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stockguard_test_privileged') THEN
        CREATE ROLE stockguard_test_privileged WITH LOGIN PASSWORD 'test_priv_pass'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    ELSE
        ALTER ROLE stockguard_test_privileged WITH LOGIN PASSWORD 'test_priv_pass'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
    END IF;

    GRANT ALL ON SCHEMA public TO stockguard_owner;
    ALTER SCHEMA public OWNER TO stockguard_owner;
    GRANT USAGE ON SCHEMA public TO stockguard_app;
    GRANT USAGE ON SCHEMA public TO stockguard_test_privileged;

    ALTER DEFAULT PRIVILEGES FOR ROLE stockguard_owner IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO stockguard_app;
    ALTER DEFAULT PRIVILEGES FOR ROLE stockguard_owner IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO stockguard_app;
END;
$$;
