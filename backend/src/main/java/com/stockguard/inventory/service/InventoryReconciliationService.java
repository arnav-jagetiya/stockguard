package com.stockguard.inventory.service;

import com.stockguard.inventory.dto.ReconciliationReport;
import com.stockguard.inventory.repository.JdbcInventoryOperations;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Service providing read-only mathematical reconciliation between stored inventory_balances
 * and append-only inventory_ledger_entries per ADR-004.
 */
@Service
@Profile("!test")
@Transactional(readOnly = true)
public class InventoryReconciliationService {

    private final JdbcInventoryOperations jdbcInventoryOperations;

    public InventoryReconciliationService(JdbcInventoryOperations jdbcInventoryOperations) {
        this.jdbcInventoryOperations = jdbcInventoryOperations;
    }

    public ReconciliationReport reconcile(UUID organizationId) {
        return jdbcInventoryOperations.reconcile(organizationId);
    }
}
