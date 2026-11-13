-- PostgreSQL initialization script for dual-role principle of least privilege (ADR-002)

-- 1. Create migration owner role (DDL / schema management)
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'stockguard_owner') THEN
        CREATE ROLE stockguard_owner WITH LOGIN PASSWORD 'test_owner_pass';
    END IF;
END $$;

-- 2. Create runtime application role (DML only, no DDL)
DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'stockguard_app') THEN
        CREATE ROLE stockguard_app WITH LOGIN PASSWORD 'test_app_pass';
    END IF;
END $$;

-- 3. Grant database and schema privileges to migration owner
GRANT ALL PRIVILEGES ON DATABASE stockguard TO stockguard_owner;

\connect stockguard

GRANT ALL ON SCHEMA public TO stockguard_owner;
GRANT ALL ON SCHEMA public TO stockguard;

-- 4. Grant runtime DML privileges to stockguard_app
GRANT USAGE ON SCHEMA public TO stockguard_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO stockguard_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO stockguard_app;

ALTER DEFAULT PRIVILEGES FOR ROLE stockguard_owner IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO stockguard_app;

ALTER DEFAULT PRIVILEGES FOR ROLE stockguard_owner IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO stockguard_app;

ALTER DEFAULT PRIVILEGES FOR ROLE stockguard IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO stockguard_app;

ALTER DEFAULT PRIVILEGES FOR ROLE stockguard IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO stockguard_app;
