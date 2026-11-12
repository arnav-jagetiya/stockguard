-- ============================================================================
-- STOCKGUARD — V3 Authentication & Session Management Migration
-- Authoritative Sources:
--   - docs/architecture/adr/ADR-010-authentication-and-session-management.md
--   - docs/architecture/adr/ADR-011-authorization-and-organization-access-control.md
--   - docs/security/security-architecture.md
--   - docs/security/role-permission-matrix.md
--   - docs/security/v3-auth-schema-proposal.md
--   - docs/security/milestone-3-implementation-plan.md
-- Engine: PostgreSQL 16
-- Scope: auth_sessions, refresh_tokens, constraints, partial unique index,
--        triggers, and runtime role permission grants
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. auth_sessions
-- ----------------------------------------------------------------------------
CREATE TABLE auth_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL,
    user_agent VARCHAR(500),
    ip_address VARCHAR(45),
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    revoked_at TIMESTAMPTZ,
    revocation_reason VARCHAR(100),
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_auth_sessions_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_auth_sessions_revocation_pair CHECK (
        (revoked = FALSE AND revoked_at IS NULL AND revocation_reason IS NULL) OR
        (revoked = TRUE AND revoked_at IS NOT NULL AND revocation_reason IS NOT NULL)
    ),
    CONSTRAINT chk_auth_sessions_expires_after_created CHECK (expires_at > created_at)
);

CREATE INDEX idx_auth_sessions_user ON auth_sessions (user_id);
CREATE INDEX idx_auth_sessions_expires ON auth_sessions (expires_at) WHERE revoked = FALSE;

-- ----------------------------------------------------------------------------
-- 2. refresh_tokens
-- ----------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id UUID NOT NULL,
    token_hash CHAR(64) NOT NULL,
    sequence_number INTEGER NOT NULL,
    consumed_at TIMESTAMPTZ,
    replaced_by_token_id UUID,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT uq_refresh_tokens_session_seq UNIQUE (session_id, sequence_number),
    CONSTRAINT uq_refresh_tokens_session_token UNIQUE (session_id, id),
    CONSTRAINT chk_refresh_tokens_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_refresh_tokens_seq CHECK (sequence_number > 0),
    CONSTRAINT chk_refresh_tokens_consumed_replacement CHECK (
        (consumed_at IS NULL AND replaced_by_token_id IS NULL) OR
        (consumed_at IS NOT NULL AND replaced_by_token_id IS NOT NULL)
    ),
    CONSTRAINT chk_refresh_tokens_expires_after_created CHECK (expires_at > created_at),
    CONSTRAINT fk_refresh_tokens_session FOREIGN KEY (session_id) REFERENCES auth_sessions(id) ON DELETE CASCADE,
    CONSTRAINT fk_refresh_tokens_same_session_replacement FOREIGN KEY (session_id, replaced_by_token_id)
        REFERENCES refresh_tokens(session_id, id)
        ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
);

-- Active refresh-token uniqueness: at most one active refresh token per session
CREATE UNIQUE INDEX uq_refresh_tokens_one_active_per_session
    ON refresh_tokens (session_id)
    WHERE consumed_at IS NULL;

-- Supporting traversal and cleanup indexes
CREATE INDEX idx_refresh_tokens_session ON refresh_tokens (session_id);
CREATE INDEX idx_refresh_tokens_active ON refresh_tokens (token_hash) WHERE consumed_at IS NULL;
CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);

-- ----------------------------------------------------------------------------
-- 3. One-Way Transition & Column Immutability Trigger
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION enforce_refresh_token_update_rules()
RETURNS trigger AS $$
BEGIN
    -- 1. Immutable Columns Protection
    IF NEW.id IS DISTINCT FROM OLD.id OR
       NEW.session_id IS DISTINCT FROM OLD.session_id OR
       NEW.token_hash IS DISTINCT FROM OLD.token_hash OR
       NEW.sequence_number IS DISTINCT FROM OLD.sequence_number OR
       NEW.created_at IS DISTINCT FROM OLD.created_at OR
       NEW.expires_at IS DISTINCT FROM OLD.expires_at THEN
        RAISE EXCEPTION 'Updates to immutable columns of refresh_tokens (id, session_id, token_hash, sequence_number, created_at, expires_at) are prohibited.'
            USING ERRCODE = '55000';
    END IF;

    -- 2. Consumed Tokens are Terminal and Immutable
    IF OLD.consumed_at IS NOT NULL OR OLD.replaced_by_token_id IS NOT NULL THEN
        RAISE EXCEPTION 'Consumed refresh tokens are terminal and cannot be modified, reactivated, or re-pointed (token id: %).', OLD.id
            USING ERRCODE = '55000';
    END IF;

    -- 3. One-Way State Transition: ACTIVE -> CONSUMED
    IF NEW.consumed_at IS NULL OR NEW.replaced_by_token_id IS NULL THEN
        RAISE EXCEPTION 'Invalid transition: refresh tokens can only transition from ACTIVE to CONSUMED by setting both consumed_at and replaced_by_token_id.'
            USING ERRCODE = '55000';
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_refresh_tokens_enforce_one_way_transition
    BEFORE UPDATE ON refresh_tokens
    FOR EACH ROW
    EXECUTE FUNCTION enforce_refresh_token_update_rules();

-- ----------------------------------------------------------------------------
-- 4. Individual Deletion Protection Trigger
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION prevent_direct_refresh_token_delete()
RETURNS trigger AS $$
BEGIN
    -- If the parent auth_sessions row still exists, direct deletion is prohibited
    IF EXISTS (SELECT 1 FROM auth_sessions WHERE id = OLD.session_id) THEN
        RAISE EXCEPTION 'Direct deletion of refresh_token (id: %) is prohibited. Tokens are append-only and may only be purged via auth_sessions cleanup.', OLD.id
            USING ERRCODE = '55000';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_refresh_tokens_prevent_direct_delete
    BEFORE DELETE ON refresh_tokens
    FOR EACH ROW
    EXECUTE FUNCTION prevent_direct_refresh_token_delete();

-- ----------------------------------------------------------------------------
-- 5. Truncate Protection Trigger for refresh_tokens
-- ----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION prevent_refresh_tokens_truncate()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'TRUNCATE is prohibited on refresh_tokens: tokens may only be purged via auth_sessions cleanup.'
        USING ERRCODE = '55000';
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_refresh_tokens_prevent_truncate
    BEFORE TRUNCATE ON refresh_tokens
    FOR EACH STATEMENT
    EXECUTE FUNCTION prevent_refresh_tokens_truncate();

-- ----------------------------------------------------------------------------
-- 6. Runtime Application Role Configuration & Grants (Conditional)
-- ----------------------------------------------------------------------------
-- In environments where stockguard_app has been provisioned, configure least-privilege DML:
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stockguard_app') THEN
        REVOKE ALL ON refresh_tokens FROM stockguard_app;
        GRANT SELECT, INSERT ON refresh_tokens TO stockguard_app;
        GRANT UPDATE (consumed_at, replaced_by_token_id) ON refresh_tokens TO stockguard_app;
        GRANT SELECT, INSERT, UPDATE, DELETE ON auth_sessions TO stockguard_app;
    END IF;
END;
$$ LANGUAGE plpgsql;
