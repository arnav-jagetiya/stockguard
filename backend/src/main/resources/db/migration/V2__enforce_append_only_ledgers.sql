-- ============================================================================
-- STOCKGUARD — V2 Append-Only Database Enforcement
-- Authoritative Source: DATABASE_SCHEMA_SPECIFICATION.md, Section 11
-- Engine: PostgreSQL 16
-- Scope: Database-level immutability enforcement for inventory_ledger_entries
--        and audit_logs
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Reusable trigger function rejecting UPDATE, DELETE, and TRUNCATE
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION prevent_immutable_table_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Table % is append-only: % operations are strictly prohibited',
        TG_TABLE_NAME,
        TG_OP
        USING ERRCODE = 'restrict_violation';
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- ----------------------------------------------------------------------------
-- 2. Statement-level trigger for inventory_ledger_entries
-- ----------------------------------------------------------------------------
CREATE TRIGGER trg_prevent_inventory_ledger_entries_mutation
    BEFORE UPDATE OR DELETE OR TRUNCATE
    ON inventory_ledger_entries
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_immutable_table_mutation();

-- ----------------------------------------------------------------------------
-- 3. Statement-level trigger for audit_logs
-- ----------------------------------------------------------------------------
CREATE TRIGGER trg_prevent_audit_logs_mutation
    BEFORE UPDATE OR DELETE OR TRUNCATE
    ON audit_logs
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_immutable_table_mutation();
