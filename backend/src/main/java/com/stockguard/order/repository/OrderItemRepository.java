package com.stockguard.order.repository;

import com.stockguard.order.domain.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OrderItemRepository extends JpaRepository<OrderItem, UUID> {

    List<OrderItem> findAllByOrganizationIdAndOrderIdOrderByLineNumberAsc(UUID organizationId, UUID orderId);

    int countByOrganizationIdAndOrderId(UUID organizationId, UUID orderId);
}
