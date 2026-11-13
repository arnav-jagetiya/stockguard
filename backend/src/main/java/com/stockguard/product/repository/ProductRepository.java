package com.stockguard.product.repository;

import com.stockguard.product.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findByIdAndOrganizationId(UUID id, UUID organizationId);

    boolean existsByOrganizationIdAndNormalizedSku(UUID organizationId, String normalizedSku);

    Optional<Product> findByOrganizationIdAndNormalizedSku(UUID organizationId, String normalizedSku);

    List<Product> findAllByOrganizationIdOrderByNormalizedSkuAsc(UUID organizationId);

    List<Product> findAllByOrganizationIdAndActiveTrueOrderByNormalizedSkuAsc(UUID organizationId);

    @Query("""
            SELECT p FROM Product p
            WHERE p.organizationId = :organizationId
              AND (:activeOnly = false OR p.active = true)
              AND (:query IS NULL OR :query = '' OR
                   LOWER(p.name) LIKE LOWER(CONCAT('%', :query, '%')) OR
                   LOWER(p.sku) LIKE LOWER(CONCAT('%', :query, '%')) OR
                   LOWER(p.normalizedSku) LIKE LOWER(CONCAT('%', :query, '%')))
            ORDER BY p.normalizedSku ASC
            """)
    List<Product> searchProducts(
            @Param("organizationId") UUID organizationId,
            @Param("query") String query,
            @Param("activeOnly") boolean activeOnly
    );
}
