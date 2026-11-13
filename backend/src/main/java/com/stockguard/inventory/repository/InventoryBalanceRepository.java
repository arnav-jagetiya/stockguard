package com.stockguard.inventory.repository;

import com.stockguard.inventory.domain.InventoryBalance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InventoryBalanceRepository extends JpaRepository<InventoryBalance, UUID> {

    Optional<InventoryBalance> findByOrganizationIdAndProductIdAndWarehouseId(
            UUID organizationId,
            UUID productId,
            UUID warehouseId
    );

    List<InventoryBalance> findAllByOrganizationId(UUID organizationId);

    List<InventoryBalance> findAllByOrganizationIdAndProductId(UUID organizationId, UUID productId);

    List<InventoryBalance> findAllByOrganizationIdAndWarehouseId(UUID organizationId, UUID warehouseId);
}
