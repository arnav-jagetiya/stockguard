package com.stockguard.warehouse.controller;

import com.stockguard.security.context.OrganizationContext;
import com.stockguard.security.context.OrganizationContextHolder;
import com.stockguard.warehouse.dto.CreateWarehouseRequest;
import com.stockguard.warehouse.dto.UpdateWarehouseRequest;
import com.stockguard.warehouse.dto.WarehouseResponse;
import com.stockguard.warehouse.service.WarehouseService;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/warehouses")
@Profile("!test")
public class WarehouseController {

    private final WarehouseService warehouseService;

    public WarehouseController(WarehouseService warehouseService) {
        this.warehouseService = warehouseService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('warehouse:manage')")
    public ResponseEntity<WarehouseResponse> createWarehouse(@Valid @RequestBody CreateWarehouseRequest request) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        WarehouseResponse response = warehouseService.createWarehouse(context.organizationId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('warehouse:view')")
    public ResponseEntity<List<WarehouseResponse>> listWarehouses(
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "activeOnly", defaultValue = "false") boolean activeOnly
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        List<WarehouseResponse> response = warehouseService.listWarehouses(context.organizationId(), search, activeOnly);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{warehouseId}")
    @PreAuthorize("hasAuthority('warehouse:view')")
    public ResponseEntity<WarehouseResponse> getWarehouse(@PathVariable UUID warehouseId) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        WarehouseResponse response = warehouseService.getWarehouse(context.organizationId(), warehouseId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{warehouseId}")
    @PreAuthorize("hasAuthority('warehouse:manage')")
    public ResponseEntity<WarehouseResponse> updateWarehouse(
            @PathVariable UUID warehouseId,
            @Valid @RequestBody UpdateWarehouseRequest request
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        WarehouseResponse response = warehouseService.updateWarehouse(context.organizationId(), warehouseId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{warehouseId}")
    @PreAuthorize("hasAuthority('warehouse:manage')")
    public ResponseEntity<WarehouseResponse> deactivateWarehouse(@PathVariable UUID warehouseId) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        WarehouseResponse response = warehouseService.deactivateWarehouse(context.organizationId(), warehouseId);
        return ResponseEntity.ok(response);
    }
}
