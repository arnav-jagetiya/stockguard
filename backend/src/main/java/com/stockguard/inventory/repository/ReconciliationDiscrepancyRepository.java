package com.stockguard.inventory.repository;

import com.stockguard.inventory.domain.DiscrepancyStatus;
import com.stockguard.inventory.domain.ReconciliationDiscrepancyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReconciliationDiscrepancyRepository extends JpaRepository<ReconciliationDiscrepancyEntity, UUID> {

    Optional<ReconciliationDiscrepancyEntity> findByIdAndOrganizationId(UUID id, UUID organizationId);

    List<ReconciliationDiscrepancyEntity> findByOrganizationIdAndStatusIn(
            UUID organizationId,
            Collection<DiscrepancyStatus> statuses
    );

    Optional<ReconciliationDiscrepancyEntity> findByOrganizationIdAndProductIdAndWarehouseIdAndStatusIn(
            UUID organizationId,
            UUID productId,
            UUID warehouseId,
            Collection<DiscrepancyStatus> statuses
    );

    long countByOrganizationIdAndStatusIn(
            UUID organizationId,
            Collection<DiscrepancyStatus> statuses
    );

    @Query("""
        SELECT d FROM ReconciliationDiscrepancyEntity d
        WHERE d.organizationId = :organizationId
          AND d.productId = :productId
          AND d.warehouseId = :warehouseId
          AND d.status IN :statuses
    """)
    Optional<ReconciliationDiscrepancyEntity> findActiveByPosition(
            @Param("organizationId") UUID organizationId,
            @Param("productId") UUID productId,
            @Param("warehouseId") UUID warehouseId,
            @Param("statuses") Collection<DiscrepancyStatus> statuses
    );
}
