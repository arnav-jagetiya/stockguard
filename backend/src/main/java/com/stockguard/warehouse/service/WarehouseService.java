package com.stockguard.warehouse.service;

import com.stockguard.shared.exception.DuplicateResourceException;
import com.stockguard.shared.exception.ResourceNotFoundException;
import com.stockguard.warehouse.domain.Warehouse;
import com.stockguard.warehouse.dto.CreateWarehouseRequest;
import com.stockguard.warehouse.dto.UpdateWarehouseRequest;
import com.stockguard.warehouse.dto.WarehouseResponse;
import com.stockguard.warehouse.repository.WarehouseRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Profile("!test")
@Transactional
public class WarehouseService {

    private final WarehouseRepository warehouseRepository;

    public WarehouseService(WarehouseRepository warehouseRepository) {
        this.warehouseRepository = warehouseRepository;
    }

    public WarehouseResponse createWarehouse(UUID organizationId, CreateWarehouseRequest request) {
        String normalizedCode = Warehouse.normalizeCode(request.code());
        if (warehouseRepository.existsByOrganizationIdAndNormalizedCode(organizationId, normalizedCode)) {
            throw new DuplicateResourceException(
                    "Warehouse with code '" + normalizedCode + "' already exists in organization " + organizationId
            );
        }

        Warehouse warehouse = new Warehouse(
                organizationId,
                request.code(),
                request.name(),
                request.location(),
                request.priority()
        );

        Warehouse saved = warehouseRepository.save(warehouse);
        return WarehouseResponse.fromDomain(saved);
    }

    @Transactional(readOnly = true)
    public WarehouseResponse getWarehouse(UUID organizationId, UUID warehouseId) {
        Warehouse warehouse = warehouseRepository.findByIdAndOrganizationId(warehouseId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + warehouseId));
        return WarehouseResponse.fromDomain(warehouse);
    }

    @Transactional(readOnly = true)
    public List<WarehouseResponse> listWarehouses(UUID organizationId, String query, boolean activeOnly) {
        List<Warehouse> warehouses = warehouseRepository.searchWarehouses(organizationId, query, activeOnly);
        return warehouses.stream()
                .map(WarehouseResponse::fromDomain)
                .toList();
    }

    public WarehouseResponse updateWarehouse(UUID organizationId, UUID warehouseId, UpdateWarehouseRequest request) {
        Warehouse warehouse = warehouseRepository.findByIdAndOrganizationId(warehouseId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + warehouseId));

        warehouse.updateDetails(request.name(), request.location(), request.priority());
        Warehouse saved = warehouseRepository.save(warehouse);
        return WarehouseResponse.fromDomain(saved);
    }

    public WarehouseResponse deactivateWarehouse(UUID organizationId, UUID warehouseId) {
        Warehouse warehouse = warehouseRepository.findByIdAndOrganizationId(warehouseId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + warehouseId));

        warehouse.deactivate();
        Warehouse saved = warehouseRepository.save(warehouse);
        return WarehouseResponse.fromDomain(saved);
    }
}
