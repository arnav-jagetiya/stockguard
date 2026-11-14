package com.stockguard.order.service;

import com.stockguard.audit.service.AuditLogService;
import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.repository.JdbcInventoryOperations.ProductWarehouseKey;
import com.stockguard.order.domain.*;
import com.stockguard.order.dto.*;
import com.stockguard.order.exception.InvalidOrderStateException;
import com.stockguard.order.repository.JdbcOrderOperations;
import com.stockguard.order.repository.OrderItemRepository;
import com.stockguard.order.repository.OrderRepository;
import com.stockguard.reservation.domain.ReservationStatus;
import com.stockguard.reservation.repository.JdbcReservationOperations;
import com.stockguard.reservation.repository.JdbcReservationOperations.LockedAllocationInfo;
import com.stockguard.reservation.repository.JdbcReservationOperations.LockedOrderInfo;
import com.stockguard.reservation.repository.JdbcReservationOperations.LockedReservationInfo;
import com.stockguard.shared.exception.InsufficientStockException;
import com.stockguard.shared.exception.InvalidOperationException;
import com.stockguard.shared.exception.ResourceNotFoundException;
import com.stockguard.shared.idempotency.IdempotencyService;
import com.stockguard.transfer.service.TransferService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Service managing customer order creation, line item persistence with immutable snapshots,
 * order lifecycle transitions (confirmation, cancellation, fulfillment), and retrieval
 * within tenant organization boundaries.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final JdbcOrderOperations jdbcOrderOperations;
    private final JdbcReservationOperations jdbcReservationOperations;
    private final IdempotencyService idempotencyService;
    private final PlatformTransactionManager transactionManager;
    private final AuditLogService auditLogService;

    @Autowired
    public OrderService(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            JdbcOrderOperations jdbcOrderOperations,
            JdbcReservationOperations jdbcReservationOperations,
            IdempotencyService idempotencyService,
            PlatformTransactionManager transactionManager,
            AuditLogService auditLogService
    ) {
        this.orderRepository = Objects.requireNonNull(orderRepository, "orderRepository must not be null");
        this.orderItemRepository = Objects.requireNonNull(orderItemRepository, "orderItemRepository must not be null");
        this.jdbcOrderOperations = Objects.requireNonNull(jdbcOrderOperations, "jdbcOrderOperations must not be null");
        this.jdbcReservationOperations = jdbcReservationOperations;
        this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService, "auditLogService must not be null");
    }

    public OrderService(
            OrderRepository orderRepository,
            OrderItemRepository orderItemRepository,
            JdbcOrderOperations jdbcOrderOperations,
            IdempotencyService idempotencyService,
            PlatformTransactionManager transactionManager,
            AuditLogService auditLogService
    ) {
        this(orderRepository, orderItemRepository, jdbcOrderOperations, null, idempotencyService, transactionManager, auditLogService);
    }

    /**
     * Creates a new customer order with line items in PENDING status.
     * Enforces monetary precision, locks referenced products under FOR SHARE, captures
     * immutable snapshots, and guarantees atomic execution with IdempotencyService.
     */
    public OrderResponse createOrder(
            UUID organizationId,
            UUID actorUserId,
            CreateOrderRequest request,
            String idempotencyKey
    ) {
        validateCreateOrderRequest(request);

        String currency = (request.currency() != null && !request.currency().isBlank())
                ? request.currency().trim().toUpperCase(Locale.ROOT)
                : "USD";

        // Calculate total amount authoritatively using BigDecimal
        BigDecimal totalAmount = BigDecimal.ZERO;
        for (OrderItemRequest item : request.items()) {
            BigDecimal validatedPrice = MonetaryValidator.validate(item.unitPrice(), "unitPrice");
            BigDecimal lineTotal = validatedPrice.multiply(BigDecimal.valueOf(item.quantity()));
            totalAmount = totalAmount.add(lineTotal);
        }
        final BigDecimal validatedTotal = MonetaryValidator.validate(totalAmount, "totalAmount");

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        return template.execute(status -> idempotencyService.execute(
                organizationId,
                actorUserId,
                "ORDER_CREATE",
                idempotencyKey,
                request,
                OrderResponse.class,
                201, // HTTP 201 Created
                () -> executeOrderCreationLogic(organizationId, actorUserId, request, currency, validatedTotal)
        ));
    }

    private OrderResponse executeOrderCreationLogic(
            UUID organizationId,
            UUID actorUserId,
            CreateOrderRequest request,
            String currency,
            BigDecimal totalAmount
    ) {
        List<UUID> productIds = request.items().stream()
                .map(OrderItemRequest::productId)
                .toList();

        // 1. Lock referenced products in canonical ascending ID order under FOR SHARE
        List<JdbcOrderOperations.LockedProductSnapshot> snapshots =
                jdbcOrderOperations.lockAndGetProductsForSnapshot(organizationId, productIds);

        Map<UUID, JdbcOrderOperations.LockedProductSnapshot> snapshotMap = new HashMap<>();
        for (JdbcOrderOperations.LockedProductSnapshot p : snapshots) {
            snapshotMap.put(p.id(), p);
        }

        // 2. Validate product existence and active status
        for (UUID pid : productIds) {
            JdbcOrderOperations.LockedProductSnapshot p = snapshotMap.get(pid);
            if (p == null) {
                throw new ResourceNotFoundException("Product not found: " + pid);
            }
            if (!p.active()) {
                throw new InvalidOperationException("Product is inactive: " + p.sku());
            }
        }

        // 3. Collision-safe conflict-aware order number insertion inside the same transaction
        UUID orderId = UUID.randomUUID();
        String finalOrderNumber = null;
        int maxAttempts = 3;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            String candidateOrderNumber = jdbcOrderOperations.generateOrderNumber();
            Optional<UUID> insertedId = jdbcOrderOperations.tryInsertOrder(
                    orderId,
                    organizationId,
                    candidateOrderNumber,
                    request.customerReference(),
                    request.externalOrderReference(),
                    currency,
                    totalAmount,
                    actorUserId
            );

            if (insertedId.isPresent()) {
                finalOrderNumber = candidateOrderNumber;
                break;
            }
            log.warn("Order number collision on {} (attempt {}/{}). Retrying...", candidateOrderNumber, attempt, maxAttempts);
        }

        if (finalOrderNumber == null) {
            throw new IllegalStateException("Failed to generate unique order number after " + maxAttempts + " attempts");
        }

        // 4. Insert order items with immutable snapshots inside the same transaction
        int lineNumber = 1;
        for (OrderItemRequest item : request.items()) {
            JdbcOrderOperations.LockedProductSnapshot p = snapshotMap.get(item.productId());
            BigDecimal validatedPrice = MonetaryValidator.validate(item.unitPrice(), "unitPrice");
            jdbcOrderOperations.insertOrderItem(
                    UUID.randomUUID(),
                    organizationId,
                    orderId,
                    lineNumber++,
                    item.productId(),
                    p.sku(),
                    p.name(),
                    item.quantity(),
                    validatedPrice
            );
        }

        auditLogService.logOrderCreated(
                organizationId,
                actorUserId,
                orderId,
                finalOrderNumber,
                currency,
                totalAmount,
                request.items().size(),
                request.customerReference(),
                request.externalOrderReference()
        );

        Instant now = Instant.now();
        return new OrderResponse(
                orderId,
                organizationId,
                finalOrderNumber,
                request.customerReference(),
                request.externalOrderReference(),
                OrderStatus.PENDING,
                currency,
                totalAmount,
                request.items().size(),
                now,
                now
        );
    }

    public OrderDetailResponse getOrder(UUID organizationId, UUID orderId) {
        Order order = orderRepository.findByIdAndOrganizationId(orderId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        List<OrderItem> items = orderItemRepository.findAllByOrganizationIdAndOrderIdOrderByLineNumberAsc(organizationId, orderId);
        List<OrderItemResponse> itemResponses = items.stream()
                .map(OrderItemResponse::fromDomain)
                .toList();

        return OrderDetailResponse.fromDomain(order, itemResponses);
    }

    public List<OrderResponse> listOrders(UUID organizationId, OrderStatus status, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        List<Order> orders = orderRepository.listOrders(organizationId, status);

        return orders.stream()
                .limit(boundedLimit)
                .map(o -> {
                    int itemCount = orderItemRepository.countByOrganizationIdAndOrderId(organizationId, o.getId());
                    return OrderResponse.fromDomain(o, itemCount);
                })
                .toList();
    }

    private void validateCreateOrderRequest(CreateOrderRequest request) {
        if (request == null) {
            throw new InvalidOperationException("Create order request must not be null");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new InvalidOperationException("Order must contain at least one item line");
        }
        for (OrderItemRequest item : request.items()) {
            if (item.productId() == null) {
                throw new InvalidOperationException("Product ID must not be null");
            }
            if (item.quantity() == null || item.quantity() <= 0) {
                throw new InvalidOperationException("Quantity must be strictly positive: " + item.quantity());
            }
            MonetaryValidator.validate(item.unitPrice(), "unitPrice");
        }
    }

    /**
     * Confirms an order (PENDING -> CONFIRMED).
     * <p>
     * Requirements:
     * <ul>
     *   <li>Order must be in PENDING status.</li>
     *   <li>Order must have an ACTIVE reservation.</li>
     *   <li>Clears reservation expires_at (making it non-expiring).</li>
     *   <li>Leaves inventory balances and ledgers untouched.</li>
     * </ul>
     */
    public OrderResponse confirmOrder(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId,
            String idempotencyKey
    ) {
        if (orderId == null) {
            throw new InvalidOperationException("Order ID must not be null");
        }
        ensureReservationOperationsAvailable();

        int maxAttempts = 3;
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return executeConfirmOrderInTransaction(organizationId, actorUserId, orderId, idempotencyKey);
            } catch (Exception ex) {
                if (attempt < maxAttempts && TransferService.isTransientLockFailure(ex)) {
                    log.warn("Transient lock failure on order confirmation attempt {}/{}: {}. Retrying...",
                            attempt, maxAttempts, ex.getMessage());
                    try {
                        Thread.sleep(25L * attempt + (long) (Math.random() * 25));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Order confirmation interrupted during retry backoff", ie);
                    }
                } else {
                    throw ex;
                }
            }
        }
    }

    private OrderResponse executeConfirmOrderInTransaction(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId,
            String idempotencyKey
    ) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        return template.execute(status -> idempotencyService.execute(
                organizationId,
                actorUserId,
                "ORDER_CONFIRMATION",
                idempotencyKey,
                new OrderLifecycleActionRequest(orderId),
                OrderResponse.class,
                200,
                () -> executeConfirmOrderLogic(organizationId, actorUserId, orderId)
        ));
    }

    private OrderResponse executeConfirmOrderLogic(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId
    ) {
        // 1. Lock order FOR UPDATE
        LockedOrderInfo order = jdbcReservationOperations.lockOrder(organizationId, orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        if (order.status() != OrderStatus.PENDING) {
            throw new InvalidOrderStateException("Order must be in PENDING status to confirm, but was: " + order.status());
        }

        // 2. Lock reservation FOR UPDATE
        LockedReservationInfo reservation = jdbcReservationOperations.lockReservationForOrder(organizationId, orderId)
                .orElseThrow(() -> new InvalidOrderStateException("Order cannot be confirmed without an ACTIVE reservation"));

        if (!"ACTIVE".equals(reservation.status())) {
            throw new InvalidOrderStateException("Order reservation must be ACTIVE to confirm, but was: " + reservation.status());
        }

        // 3. Clear reservation expires_at (making it non-expiring)
        jdbcReservationOperations.clearReservationExpiresAt(organizationId, reservation.id());

        // 4. Update order status to CONFIRMED
        jdbcOrderOperations.updateOrderStatus(organizationId, orderId, OrderStatus.CONFIRMED);

        int itemCount = orderItemRepository.countByOrganizationIdAndOrderId(organizationId, orderId);
        Order updated = orderRepository.findByIdAndOrganizationId(orderId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        auditLogService.logOrderConfirmed(
                organizationId,
                actorUserId,
                orderId,
                updated.getOrderNumber(),
                reservation.id()
        );

        return OrderResponse.fromDomain(updated, itemCount);
    }

    /**
     * Cancels an order (PENDING or CONFIRMED -> CANCELLED).
     * <p>
     * If an ACTIVE reservation exists, releases all reserved stock and records RELEASE ledger entries.
     * If an unreserved PENDING order is cancelled, transitions directly without stock mutations.
     */
    public OrderResponse cancelOrder(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId,
            String idempotencyKey
    ) {
        if (orderId == null) {
            throw new InvalidOperationException("Order ID must not be null");
        }
        ensureReservationOperationsAvailable();

        int maxAttempts = 3;
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return executeCancelOrderInTransaction(organizationId, actorUserId, orderId, idempotencyKey);
            } catch (Exception ex) {
                if (attempt < maxAttempts && TransferService.isTransientLockFailure(ex)) {
                    log.warn("Transient lock failure on order cancellation attempt {}/{}: {}. Retrying...",
                            attempt, maxAttempts, ex.getMessage());
                    try {
                        Thread.sleep(25L * attempt + (long) (Math.random() * 25));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Order cancellation interrupted during retry backoff", ie);
                    }
                } else {
                    throw ex;
                }
            }
        }
    }

    private OrderResponse executeCancelOrderInTransaction(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId,
            String idempotencyKey
    ) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        return template.execute(status -> idempotencyService.execute(
                organizationId,
                actorUserId,
                "ORDER_CANCELLATION",
                idempotencyKey,
                new OrderLifecycleActionRequest(orderId),
                OrderResponse.class,
                200,
                () -> executeCancelOrderLogic(organizationId, actorUserId, orderId)
        ));
    }

    private OrderResponse executeCancelOrderLogic(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId
    ) {
        // 1. Lock order FOR UPDATE
        LockedOrderInfo order = jdbcReservationOperations.lockOrder(organizationId, orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        if (order.status() != OrderStatus.PENDING && order.status() != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateException("Order in status " + order.status() + " cannot be cancelled");
        }

        // 2. Lock reservation FOR UPDATE
        Optional<LockedReservationInfo> resOpt = jdbcReservationOperations.lockReservationForOrder(organizationId, orderId);

        UUID releaseTxId = null;
        if (resOpt.isPresent()) {
            LockedReservationInfo res = resOpt.get();
            if (!"ACTIVE".equals(res.status())) {
                throw new InvalidOrderStateException("Order reservation is in status " + res.status() + ", cannot cancel order");
            }

            // 3. Retrieve allocations
            List<LockedAllocationInfo> allocations = jdbcReservationOperations.getReservationAllocations(organizationId, res.id());

            if (!allocations.isEmpty()) {
                // Aggregate quantities by product-warehouse
                Map<ProductWarehouseKey, Long> totalReservedByPw = new LinkedHashMap<>();
                Set<UUID> distinctProductIds = new TreeSet<>();
                for (LockedAllocationInfo a : allocations) {
                    ProductWarehouseKey key = new ProductWarehouseKey(a.productId(), a.warehouseId());
                    totalReservedByPw.merge(key, a.quantity(), Long::sum);
                    distinctProductIds.add(a.productId());
                }

                // Lock balances in canonical order: product_id ASC, warehouse_id ASC
                List<InventoryBalance> lockedBalances = jdbcReservationOperations.lockAndGetExistingBalances(
                        organizationId, new ArrayList<>(distinctProductIds)
                );
                Map<ProductWarehouseKey, InventoryBalance> balanceMap = new HashMap<>();
                for (InventoryBalance b : lockedBalances) {
                    balanceMap.put(new ProductWarehouseKey(b.getProductId(), b.getWarehouseId()), b);
                }

                // Insert inventory transaction header
                UUID txId = UUID.randomUUID();
                releaseTxId = txId;
                jdbcReservationOperations.insertInventoryTransaction(
                        txId, organizationId, "RELEASE", "ORDER", orderId, "Order cancelled: " + orderId, actorUserId
                );

                List<ProductWarehouseKey> sortedKeys = new ArrayList<>(totalReservedByPw.keySet());
                Collections.sort(sortedKeys);

                int entryNumber = 1;
                for (ProductWarehouseKey key : sortedKeys) {
                    long releaseQty = totalReservedByPw.get(key);
                    InventoryBalance balance = balanceMap.get(key);
                    if (balance == null) {
                        throw new IllegalStateException("Missing inventory balance for allocated product " + key.productId());
                    }

                    jdbcReservationOperations.updateInventoryBalanceRelease(balance.getId(), organizationId, releaseQty);
                    jdbcReservationOperations.insertInventoryLedgerEntry(
                            UUID.randomUUID(), organizationId, txId, entryNumber++, key.productId(), key.warehouseId(), 0L, -releaseQty
                    );
                }
            }

            // Mark reservation RELEASED
            jdbcReservationOperations.updateReservationStatus(organizationId, res.id(), ReservationStatus.RELEASED);
        } else {
            // Unreserved order
            if (order.status() == OrderStatus.CONFIRMED) {
                throw new InvalidOrderStateException("Confirmed order missing reservation");
            }
        }

        // Mark order CANCELLED
        jdbcOrderOperations.updateOrderStatus(organizationId, orderId, OrderStatus.CANCELLED);

        int itemCount = orderItemRepository.countByOrganizationIdAndOrderId(organizationId, orderId);
        Order updated = orderRepository.findByIdAndOrganizationId(orderId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        auditLogService.logOrderCancelled(
                organizationId,
                actorUserId,
                orderId,
                updated.getOrderNumber(),
                order.status().name(),
                "Order cancelled",
                resOpt.isPresent()
        );
        if (resOpt.isPresent()) {
            auditLogService.logReservationReleased(
                    organizationId,
                    actorUserId,
                    resOpt.get().id(),
                    orderId,
                    "Order cancelled",
                    releaseTxId
            );
        }

        return OrderResponse.fromDomain(updated, itemCount);
    }

    /**
     * Fulfills an order (CONFIRMED -> FULFILLED).
     * <p>
     * Requirements:
     * <ul>
     *   <li>Order must be in CONFIRMED status.</li>
     *   <li>Order must have an ACTIVE reservation.</li>
     *   <li>Decreases on_hand and reserved by allocated quantities.</li>
     *   <li>Records FULFILLMENT ledger entries (-Q on_hand, -Q reserved).</li>
     *   <li>Marks reservation CONSUMED and order FULFILLED.</li>
     * </ul>
     */
    public OrderResponse fulfillOrder(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId,
            String idempotencyKey
    ) {
        if (orderId == null) {
            throw new InvalidOperationException("Order ID must not be null");
        }
        ensureReservationOperationsAvailable();

        int maxAttempts = 3;
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return executeFulfillOrderInTransaction(organizationId, actorUserId, orderId, idempotencyKey);
            } catch (Exception ex) {
                if (attempt < maxAttempts && TransferService.isTransientLockFailure(ex)) {
                    log.warn("Transient lock failure on order fulfillment attempt {}/{}: {}. Retrying...",
                            attempt, maxAttempts, ex.getMessage());
                    try {
                        Thread.sleep(25L * attempt + (long) (Math.random() * 25));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Order fulfillment interrupted during retry backoff", ie);
                    }
                } else {
                    throw ex;
                }
            }
        }
    }

    private OrderResponse executeFulfillOrderInTransaction(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId,
            String idempotencyKey
    ) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        return template.execute(status -> idempotencyService.execute(
                organizationId,
                actorUserId,
                "ORDER_FULFILLMENT",
                idempotencyKey,
                new OrderLifecycleActionRequest(orderId),
                OrderResponse.class,
                200,
                () -> executeFulfillOrderLogic(organizationId, actorUserId, orderId)
        ));
    }

    private OrderResponse executeFulfillOrderLogic(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId
    ) {
        // 1. Lock order FOR UPDATE
        LockedOrderInfo order = jdbcReservationOperations.lockOrder(organizationId, orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        if (order.status() != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateException("Order must be in CONFIRMED status to fulfill, but was: " + order.status());
        }

        // 2. Lock reservation FOR UPDATE
        LockedReservationInfo res = jdbcReservationOperations.lockReservationForOrder(organizationId, orderId)
                .orElseThrow(() -> new InvalidOrderStateException("Order cannot be fulfilled without an ACTIVE reservation"));

        if (!"ACTIVE".equals(res.status())) {
            throw new InvalidOrderStateException("Order reservation must be ACTIVE to fulfill, but was: " + res.status());
        }

        // 3. Retrieve allocations
        List<LockedAllocationInfo> allocations = jdbcReservationOperations.getReservationAllocations(organizationId, res.id());
        if (allocations.isEmpty()) {
            throw new InvalidOperationException("Active reservation has no allocations to fulfill");
        }

        // Aggregate quantities by product-warehouse
        Map<ProductWarehouseKey, Long> totalFulfillByPw = new LinkedHashMap<>();
        Set<UUID> distinctProductIds = new TreeSet<>();
        for (LockedAllocationInfo a : allocations) {
            ProductWarehouseKey key = new ProductWarehouseKey(a.productId(), a.warehouseId());
            totalFulfillByPw.merge(key, a.quantity(), Long::sum);
            distinctProductIds.add(a.productId());
        }

        // 4. Lock balances in canonical order: product_id ASC, warehouse_id ASC
        List<InventoryBalance> lockedBalances = jdbcReservationOperations.lockAndGetExistingBalances(
                organizationId, new ArrayList<>(distinctProductIds)
        );
        Map<ProductWarehouseKey, InventoryBalance> balanceMap = new HashMap<>();
        for (InventoryBalance b : lockedBalances) {
            balanceMap.put(new ProductWarehouseKey(b.getProductId(), b.getWarehouseId()), b);
        }

        // 5. Verify allocation / balance consistency
        for (Map.Entry<ProductWarehouseKey, Long> entry : totalFulfillByPw.entrySet()) {
            ProductWarehouseKey key = entry.getKey();
            long needed = entry.getValue();
            InventoryBalance balance = balanceMap.get(key);
            if (balance == null) {
                throw new IllegalStateException("Missing inventory balance for allocated product " + key.productId());
            }
            if (balance.getOnHand() < needed || balance.getReserved() < needed) {
                throw new InsufficientStockException("Inventory balance inconsistent for fulfillment of product " +
                        key.productId() + ": onHand=" + balance.getOnHand() + ", reserved=" + balance.getReserved() + ", needed=" + needed);
            }
        }

        // 6. Insert inventory transaction header
        UUID txId = UUID.randomUUID();
        jdbcReservationOperations.insertInventoryTransaction(
                txId, organizationId, "FULFILLMENT", "ORDER", orderId, "Order fulfillment for order " + orderId, actorUserId
        );

        // 7. Update balances and insert ledger entries in canonical order
        List<ProductWarehouseKey> sortedKeys = new ArrayList<>(totalFulfillByPw.keySet());
        Collections.sort(sortedKeys);

        int entryNumber = 1;
        for (ProductWarehouseKey key : sortedKeys) {
            long fulfillQty = totalFulfillByPw.get(key);
            InventoryBalance balance = balanceMap.get(key);

            jdbcReservationOperations.updateInventoryBalanceFulfill(balance.getId(), organizationId, fulfillQty);
            jdbcReservationOperations.insertInventoryLedgerEntry(
                    UUID.randomUUID(), organizationId, txId, entryNumber++, key.productId(), key.warehouseId(), -fulfillQty, -fulfillQty
            );
        }

        // 8. Mark reservation CONSUMED
        jdbcReservationOperations.updateReservationStatus(organizationId, res.id(), ReservationStatus.CONSUMED);

        // 9. Mark order FULFILLED
        jdbcOrderOperations.updateOrderStatus(organizationId, orderId, OrderStatus.FULFILLED);

        int itemCount = orderItemRepository.countByOrganizationIdAndOrderId(organizationId, orderId);
        Order updated = orderRepository.findByIdAndOrganizationId(orderId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        auditLogService.logOrderFulfilled(
                organizationId,
                actorUserId,
                orderId,
                updated.getOrderNumber(),
                res.id(),
                txId
        );
        auditLogService.logReservationConsumed(
                organizationId,
                actorUserId,
                res.id(),
                orderId,
                txId
        );

        return OrderResponse.fromDomain(updated, itemCount);
    }

    private void ensureReservationOperationsAvailable() {
        if (this.jdbcReservationOperations == null) {
            throw new IllegalStateException("JdbcReservationOperations is required for order lifecycle operations");
        }
    }
}
