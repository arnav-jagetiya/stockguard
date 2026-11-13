package com.stockguard.warehouse.repository;

import com.stockguard.warehouse.domain.Warehouse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WarehouseRepository extends JpaRepository<Warehouse, UUID> {

    Optional<Warehouse> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndNormalizedCode(UUID organizationId, String normalizedCode);

    Optional<Warehouse> findByOrganizationIdAndNormalizedCode(UUID organizationId, String normalizedCode);

    List<Warehouse> findAllByOrganizationIdOrderByPriorityAsc(UUID organizationId);

    @Query("""
            SELECT w FROM Warehouse w
            WHERE w.organizationId = :organizationId
              AND (:activeOnly = false OR w.active = true)
              AND (:query IS NULL OR :query = '' OR
                   LOWER(w.name) LIKE LOWER(CONCAT('%', :query, '%')) OR
                   LOWER(w.code) LIKE LOWER(CONCAT('%', :query, '%')) OR
                   LOWER(w.normalizedCode) LIKE LOWER(CONCAT('%', :query, '%')) OR
                   LOWER(w.location) LIKE LOWER(CONCAT('%', :query, '%')))
            ORDER BY w.priority ASC, w.normalizedCode ASC
            """)
    List<Warehouse> searchWarehouses(
            @Param("organizationId") UUID organizationId,
            @Param("query") String query,
            @Param("activeOnly") boolean activeOnly
    );
}
