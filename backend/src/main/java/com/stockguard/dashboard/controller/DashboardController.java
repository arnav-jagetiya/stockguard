package com.stockguard.dashboard.controller;

import com.stockguard.dashboard.dto.DashboardSummaryResponse;
import com.stockguard.dashboard.dto.WarehouseDashboardResponse;
import com.stockguard.dashboard.service.DashboardService;
import com.stockguard.security.context.OrganizationContext;
import com.stockguard.security.context.OrganizationContextHolder;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

/**
 * REST controller exposing operational dashboard aggregations for inventory health and warehouse drilldowns
 * per ADR-013 and Milestone 6 Phase 2 specifications.
 * <p>
 * Endpoints require {@code dashboard:view} authority (held by ADMIN, MANAGER, OPERATOR, VIEWER).
 * Boundaries are strictly isolated by {@link OrganizationContextHolder}.
 */
@RestController
@RequestMapping("/api/dashboard")
@Profile("!test")
public class DashboardController {

    private final DashboardService dashboardService;

    public DashboardController(DashboardService dashboardService) {
        this.dashboardService = Objects.requireNonNull(dashboardService, "DashboardService must not be null");
    }

    /**
     * Retrieves tenant-wide operational dashboard summary.
     */
    @GetMapping("/summary")
    @PreAuthorize("hasAuthority('dashboard:view')")
    public ResponseEntity<DashboardSummaryResponse> getDashboardSummary() {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        DashboardSummaryResponse response = dashboardService.getDashboardSummary(context.organizationId());
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves warehouse-specific operational drilldown dashboard.
     */
    @GetMapping("/warehouses/{id}")
    @PreAuthorize("hasAuthority('dashboard:view')")
    public ResponseEntity<WarehouseDashboardResponse> getWarehouseDashboard(@PathVariable("id") UUID id) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        WarehouseDashboardResponse response = dashboardService.getWarehouseDashboard(context.organizationId(), id);
        return ResponseEntity.ok(response);
    }
}
