-- ============================================================================
-- STOCKGUARD — V1 Initial Database Migration
-- Authoritative Source: DATABASE_SCHEMA_SPECIFICATION.md
-- Engine: PostgreSQL 16
-- Scope: Exactly 16 MVP Tables
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. organizations
-- ----------------------------------------------------------------------------
CREATE TABLE organizations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_organizations_name_not_empty CHECK (length(trim(name)) > 0)
);

-- ----------------------------------------------------------------------------
-- 2. users
-- ----------------------------------------------------------------------------
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(255) NOT NULL,
    normalized_email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_normalized_email UNIQUE (normalized_email),
    CONSTRAINT chk_users_email_normalized CHECK (normalized_email = lower(trim(email)) AND length(normalized_email) > 0)
);

-- ----------------------------------------------------------------------------
-- 3. organization_memberships
-- ----------------------------------------------------------------------------
CREATE TABLE organization_memberships (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    user_id UUID NOT NULL,
    role VARCHAR(50) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_organization_memberships_org_user UNIQUE (organization_id, user_id),
    CONSTRAINT chk_organization_memberships_role CHECK (role IN ('ADMIN', 'MANAGER', 'OPERATOR', 'VIEWER')),
    CONSTRAINT fk_organization_memberships_org FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_organization_memberships_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE RESTRICT
);

CREATE INDEX idx_memberships_user ON organization_memberships (user_id);

-- ----------------------------------------------------------------------------
-- 4. products
-- ----------------------------------------------------------------------------
CREATE TABLE products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    sku VARCHAR(100) NOT NULL,
    normalized_sku VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_products_org_normalized_sku UNIQUE (organization_id, normalized_sku),
    CONSTRAINT uq_products_org_id UNIQUE (organization_id, id),
    CONSTRAINT chk_products_sku_normalized CHECK (normalized_sku = upper(trim(sku)) AND length(normalized_sku) > 0),
    CONSTRAINT chk_products_name_not_empty CHECK (length(trim(name)) > 0),
    CONSTRAINT fk_products_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT
);

CREATE INDEX idx_products_org_name ON products (organization_id, name);

-- ----------------------------------------------------------------------------
-- 5. warehouses
-- ----------------------------------------------------------------------------
CREATE TABLE warehouses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    code VARCHAR(50) NOT NULL,
    normalized_code VARCHAR(50) NOT NULL,
    name VARCHAR(255) NOT NULL,
    location VARCHAR(255),
    priority INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_warehouses_org_normalized_code UNIQUE (organization_id, normalized_code),
    CONSTRAINT uq_warehouses_org_id UNIQUE (organization_id, id),
    CONSTRAINT chk_warehouses_code_normalized CHECK (normalized_code = upper(trim(code)) AND length(normalized_code) > 0),
    CONSTRAINT chk_warehouses_name_not_empty CHECK (length(trim(name)) > 0),
    CONSTRAINT fk_warehouses_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT
);

-- ----------------------------------------------------------------------------
-- 6. inventory_balances
-- ----------------------------------------------------------------------------
CREATE TABLE inventory_balances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    product_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    on_hand BIGINT NOT NULL DEFAULT 0,
    reserved BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_inventory_balances_org_product_warehouse UNIQUE (organization_id, product_id, warehouse_id),
    CONSTRAINT chk_inventory_balances_on_hand_non_negative CHECK (on_hand >= 0),
    CONSTRAINT chk_inventory_balances_reserved_non_negative CHECK (reserved >= 0),
    CONSTRAINT chk_inventory_balances_reserved_le_on_hand CHECK (reserved <= on_hand),
    CONSTRAINT fk_inventory_balances_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_balances_product FOREIGN KEY (organization_id, product_id) REFERENCES products(organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_balances_warehouse FOREIGN KEY (organization_id, warehouse_id) REFERENCES warehouses(organization_id, id) ON DELETE RESTRICT
);

CREATE INDEX idx_balances_warehouse ON inventory_balances (organization_id, warehouse_id);

-- ----------------------------------------------------------------------------
-- 7. inventory_transactions
-- ----------------------------------------------------------------------------
CREATE TABLE inventory_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    transaction_type VARCHAR(50) NOT NULL,
    reference_type VARCHAR(50),
    reference_id UUID,
    notes TEXT,
    actor_user_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_inventory_transactions_org_id UNIQUE (organization_id, id),
    CONSTRAINT chk_inventory_transactions_type CHECK (transaction_type IN ('OPENING_BALANCE', 'RECEIPT', 'ADJUSTMENT', 'RESERVATION', 'RELEASE', 'FULFILLMENT', 'TRANSFER')),
    CONSTRAINT chk_inventory_transactions_reference_pair CHECK ((reference_type IS NULL AND reference_id IS NULL) OR (reference_type IS NOT NULL AND reference_id IS NOT NULL AND reference_type IN ('ORDER', 'TRANSFER', 'MANUAL'))),
    CONSTRAINT fk_inventory_transactions_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_transactions_actor FOREIGN KEY (organization_id, actor_user_id) REFERENCES organization_memberships(organization_id, user_id) ON DELETE RESTRICT
);

CREATE INDEX idx_transactions_org_actor ON inventory_transactions (organization_id, actor_user_id);
CREATE INDEX idx_transactions_ref ON inventory_transactions (organization_id, reference_type, reference_id);
CREATE INDEX idx_transactions_created ON inventory_transactions (organization_id, created_at DESC);

-- ----------------------------------------------------------------------------
-- 8. inventory_ledger_entries
-- ----------------------------------------------------------------------------
CREATE TABLE inventory_ledger_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    transaction_id UUID NOT NULL,
    entry_number INTEGER NOT NULL,
    product_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    on_hand_delta BIGINT NOT NULL,
    reserved_delta BIGINT NOT NULL,
    transfer_item_id UUID,
    transfer_direction VARCHAR(10),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_inventory_ledger_entries_tx_line UNIQUE (organization_id, transaction_id, entry_number),
    CONSTRAINT chk_inventory_ledger_entries_entry_number CHECK (entry_number > 0),
    CONSTRAINT chk_inventory_ledger_entries_non_zero_delta CHECK (on_hand_delta != 0 OR reserved_delta != 0),
    CONSTRAINT chk_inventory_ledger_entries_transfer_pair CHECK ((transfer_item_id IS NULL AND transfer_direction IS NULL) OR (transfer_item_id IS NOT NULL AND transfer_direction IS NOT NULL)),
    CONSTRAINT chk_inventory_ledger_entries_transfer_direction CHECK (transfer_direction IS NULL OR transfer_direction IN ('OUT', 'IN')),
    CONSTRAINT chk_inventory_ledger_entries_transfer_out CHECK (transfer_direction != 'OUT' OR (on_hand_delta < 0 AND reserved_delta = 0)),
    CONSTRAINT chk_inventory_ledger_entries_transfer_in CHECK (transfer_direction != 'IN' OR (on_hand_delta > 0 AND reserved_delta = 0)),
    CONSTRAINT fk_inventory_ledger_entries_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_ledger_entries_tx FOREIGN KEY (organization_id, transaction_id) REFERENCES inventory_transactions(organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_ledger_entries_product FOREIGN KEY (organization_id, product_id) REFERENCES products(organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_inventory_ledger_entries_warehouse FOREIGN KEY (organization_id, warehouse_id) REFERENCES warehouses(organization_id, id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX idx_ledger_transfer_out ON inventory_ledger_entries (organization_id, transfer_item_id) WHERE transfer_direction = 'OUT';
CREATE UNIQUE INDEX idx_ledger_transfer_in ON inventory_ledger_entries (organization_id, transfer_item_id) WHERE transfer_direction = 'IN';
CREATE INDEX idx_ledger_org_transfer_item ON inventory_ledger_entries (organization_id, transfer_item_id, product_id) WHERE transfer_item_id IS NOT NULL;
CREATE INDEX idx_ledger_reconciliation ON inventory_ledger_entries (organization_id, product_id, warehouse_id, created_at);
CREATE INDEX idx_ledger_org_warehouse ON inventory_ledger_entries (organization_id, warehouse_id);

-- ----------------------------------------------------------------------------
-- 9. orders
-- ----------------------------------------------------------------------------
CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    order_number VARCHAR(100) NOT NULL,
    customer_reference VARCHAR(255),
    external_order_reference VARCHAR(255),
    status VARCHAR(50) NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    total_amount NUMERIC(19,4) NOT NULL DEFAULT 0.0000,
    actor_user_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_orders_org_order_number UNIQUE (organization_id, order_number),
    CONSTRAINT uq_orders_org_id UNIQUE (organization_id, id),
    CONSTRAINT chk_orders_status CHECK (status IN ('PENDING', 'CONFIRMED', 'FULFILLING', 'FULFILLED', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT chk_orders_currency CHECK (length(currency) = 3 AND currency = upper(currency)),
    CONSTRAINT chk_orders_total_amount CHECK (total_amount >= 0),
    CONSTRAINT fk_orders_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_orders_actor FOREIGN KEY (organization_id, actor_user_id) REFERENCES organization_memberships(organization_id, user_id) ON DELETE RESTRICT
);

CREATE INDEX idx_orders_org_actor ON orders (organization_id, actor_user_id);
CREATE INDEX idx_orders_org_status_date ON orders (organization_id, status, created_at DESC);

-- ----------------------------------------------------------------------------
-- 10. order_items
-- ----------------------------------------------------------------------------
CREATE TABLE order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    order_id UUID NOT NULL,
    line_number INTEGER NOT NULL,
    product_id UUID NOT NULL,
    sku_snapshot VARCHAR(100) NOT NULL,
    name_snapshot VARCHAR(255) NOT NULL,
    quantity BIGINT NOT NULL,
    unit_price NUMERIC(19,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_order_items_org_order_line UNIQUE (organization_id, order_id, line_number),
    CONSTRAINT uq_order_items_allocation_target UNIQUE (organization_id, order_id, id, product_id),
    CONSTRAINT uq_order_items_org_id UNIQUE (organization_id, id),
    CONSTRAINT chk_order_items_line_number CHECK (line_number > 0),
    CONSTRAINT chk_order_items_quantity CHECK (quantity > 0),
    CONSTRAINT chk_order_items_unit_price CHECK (unit_price >= 0),
    CONSTRAINT fk_order_items_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_items_order FOREIGN KEY (organization_id, order_id) REFERENCES orders(organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_order_items_product FOREIGN KEY (organization_id, product_id) REFERENCES products(organization_id, id) ON DELETE RESTRICT
);

CREATE INDEX idx_order_items_org_product ON order_items (organization_id, product_id);

-- ----------------------------------------------------------------------------
-- 11. reservations
-- ----------------------------------------------------------------------------
CREATE TABLE reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    order_id UUID NOT NULL,
    status VARCHAR(50) NOT NULL,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_reservations_org_order UNIQUE (organization_id, order_id),
    CONSTRAINT uq_reservations_allocation_target UNIQUE (organization_id, id, order_id),
    CONSTRAINT uq_reservations_org_id UNIQUE (organization_id, id),
    CONSTRAINT chk_reservations_status CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT fk_reservations_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservations_order FOREIGN KEY (organization_id, order_id) REFERENCES orders(organization_id, id) ON DELETE RESTRICT
);

CREATE INDEX idx_reservations_active_expires ON reservations (expires_at) WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;

-- ----------------------------------------------------------------------------
-- 12. reservation_allocations
-- ----------------------------------------------------------------------------
CREATE TABLE reservation_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    reservation_id UUID NOT NULL,
    order_id UUID NOT NULL,
    order_item_id UUID NOT NULL,
    product_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    quantity BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_reservation_allocations_unique_assignment UNIQUE (organization_id, reservation_id, order_item_id, warehouse_id),
    CONSTRAINT chk_reservation_allocations_quantity CHECK (quantity > 0),
    CONSTRAINT fk_reservation_allocations_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_allocations_reservation FOREIGN KEY (organization_id, reservation_id, order_id) REFERENCES reservations(organization_id, id, order_id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_allocations_order_item FOREIGN KEY (organization_id, order_id, order_item_id, product_id) REFERENCES order_items(organization_id, order_id, id, product_id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_allocations_warehouse FOREIGN KEY (organization_id, warehouse_id) REFERENCES warehouses(organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_reservation_allocations_product FOREIGN KEY (organization_id, product_id) REFERENCES products(organization_id, id) ON DELETE RESTRICT
);

CREATE INDEX idx_allocations_org_reservation ON reservation_allocations (organization_id, reservation_id, order_id);
CREATE INDEX idx_allocations_org_order_item ON reservation_allocations (organization_id, order_id, order_item_id, product_id);
CREATE INDEX idx_allocations_wh_prod ON reservation_allocations (organization_id, warehouse_id, product_id);

-- ----------------------------------------------------------------------------
-- 13. transfers
-- ----------------------------------------------------------------------------
CREATE TABLE transfers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    source_warehouse_id UUID NOT NULL,
    destination_warehouse_id UUID NOT NULL,
    notes TEXT,
    actor_user_id UUID,
    completed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_transfers_org_id UNIQUE (organization_id, id),
    CONSTRAINT chk_transfers_distinct_warehouses CHECK (source_warehouse_id != destination_warehouse_id),
    CONSTRAINT fk_transfers_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfers_source_wh FOREIGN KEY (organization_id, source_warehouse_id) REFERENCES warehouses(organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfers_dest_wh FOREIGN KEY (organization_id, destination_warehouse_id) REFERENCES warehouses(organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfers_actor FOREIGN KEY (organization_id, actor_user_id) REFERENCES organization_memberships(organization_id, user_id) ON DELETE RESTRICT
);

CREATE INDEX idx_transfers_org_actor ON transfers (organization_id, actor_user_id);
CREATE INDEX idx_transfers_org_source_wh ON transfers (organization_id, source_warehouse_id);
CREATE INDEX idx_transfers_org_dest_wh ON transfers (organization_id, destination_warehouse_id);
CREATE INDEX idx_transfers_org_created ON transfers (organization_id, created_at DESC);
CREATE INDEX idx_transfers_org_completed ON transfers (organization_id, completed_at DESC);

-- ----------------------------------------------------------------------------
-- 14. transfer_items
-- ----------------------------------------------------------------------------
CREATE TABLE transfer_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    transfer_id UUID NOT NULL,
    product_id UUID NOT NULL,
    quantity BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_transfer_items_org_transfer_product UNIQUE (organization_id, transfer_id, product_id),
    CONSTRAINT uq_transfer_items_ledger_target UNIQUE (organization_id, id, product_id),
    CONSTRAINT chk_transfer_items_quantity CHECK (quantity > 0),
    CONSTRAINT fk_transfer_items_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfer_items_transfer FOREIGN KEY (organization_id, transfer_id) REFERENCES transfers(organization_id, id) ON DELETE RESTRICT,
    CONSTRAINT fk_transfer_items_product FOREIGN KEY (organization_id, product_id) REFERENCES products(organization_id, id) ON DELETE RESTRICT
);

-- Cross-table composite FK from inventory_ledger_entries to transfer_items
ALTER TABLE inventory_ledger_entries
    ADD CONSTRAINT fk_inventory_ledger_entries_transfer_item
    FOREIGN KEY (organization_id, transfer_item_id, product_id)
    REFERENCES transfer_items(organization_id, id, product_id)
    ON DELETE RESTRICT;

-- ----------------------------------------------------------------------------
-- 15. idempotency_records
-- ----------------------------------------------------------------------------
CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    principal_type VARCHAR(50) NOT NULL,
    principal_id UUID NOT NULL,
    operation_scope VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_status INTEGER,
    response_body JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_idempotency_records_scope UNIQUE (organization_id, principal_type, principal_id, operation_scope, idempotency_key),
    CONSTRAINT chk_idempotency_records_response_pair CHECK ((response_status IS NULL AND response_body IS NULL) OR (response_status IS NOT NULL AND response_body IS NOT NULL)),
    CONSTRAINT chk_idempotency_records_status_code CHECK (response_status IS NULL OR (response_status >= 100 AND response_status <= 599)),
    CONSTRAINT fk_idempotency_records_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT
);

CREATE INDEX idx_idempotency_cleanup ON idempotency_records (expires_at);

-- ----------------------------------------------------------------------------
-- 16. audit_logs
-- ----------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    actor_user_id UUID,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id UUID,
    before_state JSONB,
    after_state JSONB,
    metadata JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_logs_organization FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE RESTRICT,
    CONSTRAINT fk_audit_logs_actor FOREIGN KEY (organization_id, actor_user_id) REFERENCES organization_memberships(organization_id, user_id) ON DELETE RESTRICT
);

CREATE INDEX idx_audit_logs_org_date ON audit_logs (organization_id, created_at DESC);
CREATE INDEX idx_audit_logs_entity ON audit_logs (organization_id, entity_type, entity_id);
CREATE INDEX idx_audit_logs_actor ON audit_logs (organization_id, actor_user_id);
