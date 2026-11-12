-- ============================================================================
-- STOCKGUARD — Testcontainers Pre-Migration Role Initialization Script
-- Executed by Testcontainers BEFORE Spring Boot and Flyway start.
-- Guarantees that stockguard_app exists prior to V3 execution so that
-- V3 Flyway migration grants execute natively and conditionally.
-- ============================================================================

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stockguard_owner') THEN
        CREATE ROLE stockguard_owner NOLOGIN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stockguard_app') THEN
        CREATE ROLE stockguard_app WITH LOGIN PASSWORD 'test_app_pass'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
        GRANT USAGE ON SCHEMA public TO stockguard_app;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stockguard_test_privileged') THEN
        CREATE ROLE stockguard_test_privileged WITH LOGIN PASSWORD 'test_priv_pass'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT;
        GRANT USAGE ON SCHEMA public TO stockguard_test_privileged;
    END IF;
END;
$$;
