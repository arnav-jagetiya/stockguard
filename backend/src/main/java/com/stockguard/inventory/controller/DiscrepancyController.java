package com.stockguard.inventory.controller;

import com.stockguard.inventory.domain.DiscrepancyStatus;
import com.stockguard.inventory.dto.DiscrepancyPageResponse;
import com.stockguard.inventory.dto.DiscrepancyResponse;
import com.stockguard.inventory.dto.ReconciliationReport;
import com.stockguard.inventory.dto.ReconciliationReportResponse;
import com.stockguard.inventory.dto.UpdateDiscrepancyRequest;
import com.stockguard.inventory.service.DiscrepancyManagementService;
import com.stockguard.inventory.service.InventoryReconciliationService;
import com.stockguard.security.context.OrganizationContext;
import com.stockguard.security.context.OrganizationContextHolder;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST controller providing tenant-scoped discrepancy listing, lifecycle mutations,
 * and on-demand reconciliation triggering per ADR-012 and Milestone 6 Phase 2 specifications.
 */
@RestController
@RequestMapping("/api/inventory/reconciliation")
@Profile("!test")
public class DiscrepancyController {

    private final DiscrepancyManagementService discrepancyManagementService;
    private final InventoryReconciliationService reconciliationService;

    public DiscrepancyController(
            DiscrepancyManagementService discrepancyManagementService,
            InventoryReconciliationService reconciliationService
    ) {
        this.discrepancyManagementService = discrepancyManagementService;
        this.reconciliationService = reconciliationService;
    }

    /**
     * Triggers an on-demand reconciliation sweep for the authenticated tenant context.
     */
    @PostMapping("/trigger")
    @PreAuthorize("hasAuthority('inventory:reconcile')")
    public ResponseEntity<ReconciliationReportResponse> triggerReconciliation() {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        ReconciliationReport report = reconciliationService.reconcile(context.organizationId());
        discrepancyManagementService.syncDiscrepancies(context.organizationId(), report);
        return ResponseEntity.ok(ReconciliationReportResponse.from(report));
    }

    /**
     * Lists discrepancies with deterministic ordering, filtering, and pagination.
     */
    @GetMapping("/discrepancies")
    @PreAuthorize("hasAuthority('inventory:discrepancy:view')")
    public ResponseEntity<DiscrepancyPageResponse> listDiscrepancies(
            @RequestParam(name = "status", required = false) DiscrepancyStatus status,
            @RequestParam(name = "productId", required = false) UUID productId,
            @RequestParam(name = "warehouseId", required = false) UUID warehouseId,
            @RequestParam(name = "assignedToUserId", required = false) UUID assignedToUserId,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            @RequestParam(name = "offset", defaultValue = "0") int offset
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        DiscrepancyPageResponse response = discrepancyManagementService.listDiscrepancies(
                context.organizationId(), status, productId, warehouseId, assignedToUserId, limit, offset
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves a single discrepancy by its identifier within the tenant boundary.
     */
    @GetMapping("/discrepancies/{id}")
    @PreAuthorize("hasAuthority('inventory:discrepancy:view')")
    public ResponseEntity<DiscrepancyResponse> getDiscrepancy(@PathVariable("id") UUID id) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        DiscrepancyResponse response = discrepancyManagementService.getDiscrepancy(context.organizationId(), id);
        return ResponseEntity.ok(response);
    }

    /**
     * Updates discrepancy status, assignment, and notes per the transition matrix.
     */
    @PatchMapping("/discrepancies/{id}")
    @PreAuthorize("hasAuthority('inventory:reconcile')")
    public ResponseEntity<DiscrepancyResponse> updateDiscrepancy(
            @PathVariable("id") UUID id,
            @Valid @RequestBody UpdateDiscrepancyRequest request
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        DiscrepancyResponse response = discrepancyManagementService.updateDiscrepancy(
                context.organizationId(),
                context.userId(),
                id,
                request
        );
        return ResponseEntity.ok(response);
    }
}
