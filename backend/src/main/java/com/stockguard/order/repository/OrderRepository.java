package com.stockguard.order.repository;

import com.stockguard.order.domain.Order;
import com.stockguard.order.domain.OrderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByIdAndOrganizationId(UUID id, UUID organizationId);

    Optional<Order> findByOrganizationIdAndOrderNumber(UUID organizationId, String orderNumber);

    @Query("""
            SELECT o FROM Order o
            WHERE o.organizationId = :organizationId
              AND (:status IS NULL OR o.status = :status)
            ORDER BY o.createdAt DESC
            """)
    List<Order> listOrders(
            @Param("organizationId") UUID organizationId,
            @Param("status") OrderStatus status
    );
}
