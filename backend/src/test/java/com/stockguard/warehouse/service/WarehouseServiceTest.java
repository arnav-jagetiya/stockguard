package com.stockguard.warehouse.service;

import com.stockguard.shared.exception.DuplicateResourceException;
import com.stockguard.shared.exception.ResourceNotFoundException;
import com.stockguard.warehouse.domain.Warehouse;
import com.stockguard.warehouse.dto.CreateWarehouseRequest;
import com.stockguard.warehouse.dto.UpdateWarehouseRequest;
import com.stockguard.warehouse.dto.WarehouseResponse;
import com.stockguard.warehouse.repository.WarehouseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WarehouseServiceTest {

    private WarehouseRepository warehouseRepository;
    private WarehouseService warehouseService;
    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        warehouseRepository = mock(WarehouseRepository.class);
        warehouseService = new WarehouseService(warehouseRepository);
    }

    @Test
    @DisplayName("Create warehouse normalizes code to uppercase and persists warehouse")
    void createWarehouseNormalizesCode() {
        CreateWarehouseRequest request = new CreateWarehouseRequest("  wh-main-01  ", "Main Central Warehouse", "Building A", 1);
        when(warehouseRepository.existsByOrganizationIdAndNormalizedCode(eq(orgId), eq("WH-MAIN-01"))).thenReturn(false);
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WarehouseResponse response = warehouseService.createWarehouse(orgId, request);

        assertThat(response.code()).isEqualTo("wh-main-01");
        assertThat(response.normalizedCode()).isEqualTo("WH-MAIN-01");
        assertThat(response.name()).isEqualTo("Main Central Warehouse");
        assertThat(response.location()).isEqualTo("Building A");
        assertThat(response.priority()).isEqualTo(1);
        assertThat(response.active()).isTrue();

        ArgumentCaptor<Warehouse> captor = ArgumentCaptor.forClass(Warehouse.class);
        verify(warehouseRepository).save(captor.capture());
        assertThat(captor.getValue().getNormalizedCode()).isEqualTo("WH-MAIN-01");
    }

    @Test
    @DisplayName("Create warehouse throws DuplicateResourceException when normalized code already exists in organization")
    void createWarehouseDuplicateCodeThrowsException() {
        CreateWarehouseRequest request = new CreateWarehouseRequest("WH-EXISTING", "Existing WH", "Location", 2);
        when(warehouseRepository.existsByOrganizationIdAndNormalizedCode(eq(orgId), eq("WH-EXISTING"))).thenReturn(true);

        assertThatThrownBy(() -> warehouseService.createWarehouse(orgId, request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("code 'WH-EXISTING' already exists");

        verify(warehouseRepository, never()).save(any());
    }

    @Test
    @DisplayName("Get warehouse by ID returns warehouse when found in tenant")
    void getWarehouseFound() {
        UUID warehouseId = UUID.randomUUID();
        Warehouse warehouse = new Warehouse(orgId, "WH-1", "Warehouse 1", "Loc", 1);
        when(warehouseRepository.findByIdAndOrganizationId(warehouseId, orgId)).thenReturn(Optional.of(warehouse));

        WarehouseResponse response = warehouseService.getWarehouse(orgId, warehouseId);
        assertThat(response.name()).isEqualTo("Warehouse 1");
    }

    @Test
    @DisplayName("Get warehouse throws ResourceNotFoundException when warehouse does not exist in tenant")
    void getWarehouseNotFoundThrows() {
        UUID warehouseId = UUID.randomUUID();
        when(warehouseRepository.findByIdAndOrganizationId(warehouseId, orgId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> warehouseService.getWarehouse(orgId, warehouseId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Warehouse not found");
    }

    @Test
    @DisplayName("Update warehouse modifies details while preserving code and identity")
    void updateWarehouseModifiesDetails() {
        UUID warehouseId = UUID.randomUUID();
        Warehouse warehouse = new Warehouse(orgId, "WH-IMMUTABLE", "Original WH", "Dock 1", 1);
        when(warehouseRepository.findByIdAndOrganizationId(warehouseId, orgId)).thenReturn(Optional.of(warehouse));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateWarehouseRequest updateReq = new UpdateWarehouseRequest("Updated WH Name", "Dock 2", 5);
        WarehouseResponse response = warehouseService.updateWarehouse(orgId, warehouseId, updateReq);

        assertThat(response.name()).isEqualTo("Updated WH Name");
        assertThat(response.location()).isEqualTo("Dock 2");
        assertThat(response.priority()).isEqualTo(5);
        assertThat(response.normalizedCode()).isEqualTo("WH-IMMUTABLE");
    }

    @Test
    @DisplayName("Deactivate warehouse sets active flag to false without deleting row")
    void deactivateWarehouseSetsActiveFalse() {
        UUID warehouseId = UUID.randomUUID();
        Warehouse warehouse = new Warehouse(orgId, "WH-DEACT", "Deactivate Me", null, 2);
        assertThat(warehouse.isActive()).isTrue();

        when(warehouseRepository.findByIdAndOrganizationId(warehouseId, orgId)).thenReturn(Optional.of(warehouse));
        when(warehouseRepository.save(any(Warehouse.class))).thenAnswer(invocation -> invocation.getArgument(0));

        WarehouseResponse response = warehouseService.deactivateWarehouse(orgId, warehouseId);

        assertThat(response.active()).isFalse();
        verify(warehouseRepository, never()).delete(any());
    }
}
