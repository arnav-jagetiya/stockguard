-- ============================================================================
-- StockGuard Database Migration V4: Reconciliation Discrepancies
-- ============================================================================
-- Architecture Reference: ADR-012, Milestone 6 Phase 2 Specification
-- Target Database: PostgreSQL 16+
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Table: reconciliation_discrepancies
-- ----------------------------------------------------------------------------
CREATE TABLE reconciliation_discrepancies (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    product_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,

    -- Discrepancy Classification & Quantities
    discrepancy_type VARCHAR(30) NOT NULL,
    observed_balance_on_hand BIGINT NOT NULL DEFAULT 0,
    observed_ledger_on_hand BIGINT NOT NULL DEFAULT 0,
    observed_balance_reserved BIGINT NOT NULL DEFAULT 0,
    observed_ledger_reserved BIGINT NOT NULL DEFAULT 0,

    -- Lifecycle State Machine & Tracking
    status VARCHAR(30) NOT NULL DEFAULT 'OPEN',
    consecutive_detection_count INT NOT NULL DEFAULT 1,
    first_detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_verified_at TIMESTAMPTZ,

    -- Investigation & Assignment Metadata (Organization-Scoped)
    assigned_to_user_id UUID,
    investigation_notes TEXT,

    -- Resolution Metadata
    resolution_source VARCHAR(20),
    resolution_reason TEXT,
    resolved_at TIMESTAMPTZ,
    resolved_by_user_id UUID,

    -- Concurrency & Audit Metadata
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Constraints & Foreign Keys
    CONSTRAINT fk_discrepancies_org
        FOREIGN KEY (organization_id)
        REFERENCES organizations(id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_discrepancies_product
        FOREIGN KEY (organization_id, product_id)
        REFERENCES products(organization_id, id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_discrepancies_warehouse
        FOREIGN KEY (organization_id, warehouse_id)
        REFERENCES warehouses(organization_id, id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_discrepancies_assigned_membership
        FOREIGN KEY (organization_id, assigned_to_user_id)
        REFERENCES organization_memberships(organization_id, user_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_discrepancies_resolved_membership
        FOREIGN KEY (organization_id, resolved_by_user_id)
        REFERENCES organization_memberships(organization_id, user_id)
        ON DELETE RESTRICT,

    CONSTRAINT chk_discrepancies_type
        CHECK (discrepancy_type IN ('DELTA_MISMATCH', 'MISSING_BALANCE_ROW', 'MISSING_LEDGER_ENTRIES')),

    CONSTRAINT chk_discrepancies_status
        CHECK (status IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING', 'RESOLVED', 'REJECTED')),

    CONSTRAINT chk_discrepancies_resolution_source
        CHECK (resolution_source IS NULL OR resolution_source IN ('SYSTEM', 'HUMAN')),

    CONSTRAINT chk_discrepancies_detection_count
        CHECK (consecutive_detection_count >= 1),

    -- Comprehensive Resolution Consistency CHECK Constraint:
    -- Validates all permitted resolution permutations and rejects all invalid combinations.
    -- Uses null-safe IS NOT DISTINCT FROM and regex validation (~ '\S') to eliminate three-valued logic loopholes.
    CONSTRAINT chk_discrepancies_resolution_consistency CHECK (
        -- 1. Active states (OPEN, ACKNOWLEDGED, INVESTIGATING): zero resolution metadata
        (
            status IS NOT NULL
            AND status IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING')
            AND resolution_source IS NULL
            AND resolved_by_user_id IS NULL
            AND resolved_at IS NULL
            AND resolution_reason IS NULL
        )
        OR
        -- 2. System-resolved: source SYSTEM, resolver NULL, timestamp present, nonblank reason
        (
            status IS NOT DISTINCT FROM 'RESOLVED'
            AND resolution_source IS NOT DISTINCT FROM 'SYSTEM'
            AND resolved_by_user_id IS NULL
            AND resolved_at IS NOT NULL
            AND resolution_reason IS NOT NULL
            AND resolution_reason ~ '\S'
        )
        OR
        -- 3. Human-resolved: source HUMAN, resolver present, timestamp present, nonblank reason
        (
            status IS NOT DISTINCT FROM 'RESOLVED'
            AND resolution_source IS NOT DISTINCT FROM 'HUMAN'
            AND resolved_by_user_id IS NOT NULL
            AND resolved_at IS NOT NULL
            AND resolution_reason IS NOT NULL
            AND resolution_reason ~ '\S'
        )
        OR
        -- 4. Human-rejected: source HUMAN, resolver present, timestamp present, nonblank reason
        (
            status IS NOT DISTINCT FROM 'REJECTED'
            AND resolution_source IS NOT DISTINCT FROM 'HUMAN'
            AND resolved_by_user_id IS NOT NULL
            AND resolved_at IS NOT NULL
            AND resolution_reason IS NOT NULL
            AND resolution_reason ~ '\S'
        )
    )
);

-- ----------------------------------------------------------------------------
-- 2. Indexes
-- ----------------------------------------------------------------------------

-- Deduplication: At most one active discrepancy per (org, product, warehouse) position
CREATE UNIQUE INDEX uq_active_discrepancy_position
ON reconciliation_discrepancies (organization_id, product_id, warehouse_id)
WHERE status IN ('OPEN', 'ACKNOWLEDGED', 'INVESTIGATING');

-- Query index for dashboard counts and filtered discrepancy lists
CREATE INDEX idx_discrepancies_org_status
ON reconciliation_discrepancies (organization_id, status, last_detected_at DESC);

-- Query index for assigned user work queues
CREATE INDEX idx_discrepancies_assigned_user
ON reconciliation_discrepancies (organization_id, assigned_to_user_id, status)
WHERE assigned_to_user_id IS NOT NULL;

-- Supporting index for global last detected timestamp
CREATE INDEX idx_discrepancies_last_detected
ON reconciliation_discrepancies (last_detected_at DESC);

-- ----------------------------------------------------------------------------
-- 3. Runtime Application Role Configuration & Grants (Conditional)
-- ----------------------------------------------------------------------------
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stockguard_app') THEN
        REVOKE ALL ON reconciliation_discrepancies FROM stockguard_app;
        GRANT SELECT, INSERT, UPDATE ON reconciliation_discrepancies TO stockguard_app;
    END IF;
END;
$$ LANGUAGE plpgsql;
