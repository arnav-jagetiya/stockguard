package com.stockguard.reservation.service;

import com.stockguard.audit.service.AuditLogService;
import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.repository.JdbcInventoryOperations.ProductWarehouseKey;
import com.stockguard.order.domain.OrderItem;
import com.stockguard.order.domain.OrderStatus;
import com.stockguard.order.exception.InvalidOrderStateException;
import com.stockguard.order.repository.OrderItemRepository;
import com.stockguard.reservation.config.ReservationProperties;
import com.stockguard.reservation.domain.Reservation;
import com.stockguard.reservation.domain.ReservationAllocation;
import com.stockguard.reservation.domain.ReservationStatus;
import com.stockguard.reservation.dto.CreateReservationRequest;
import com.stockguard.reservation.dto.ReservationAllocationResponse;
import com.stockguard.reservation.dto.ReservationResponse;
import com.stockguard.reservation.repository.JdbcReservationOperations;
import com.stockguard.reservation.repository.JdbcReservationOperations.InsertedReservationHeader;
import com.stockguard.reservation.repository.JdbcReservationOperations.LockedOrderInfo;
import com.stockguard.reservation.repository.JdbcReservationOperations.LockedProductInfo;
import com.stockguard.reservation.repository.JdbcReservationOperations.LockedReservationInfo;
import com.stockguard.reservation.repository.JdbcReservationOperations.LockedWarehouseInfo;
import com.stockguard.reservation.repository.ReservationAllocationRepository;
import com.stockguard.reservation.repository.ReservationRepository;
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

import java.time.Instant;
import java.util.*;

/**
 * Service managing deterministic multi-warehouse inventory reservations.
 * <p>
 * Key invariants:
 * <ul>
 *   <li>Strict canonical lock hierarchy: Order -> Reservation -> Warehouse (id ASC) -> Product (id ASC) -> Balances (product ASC, warehouse ASC).</li>
 *   <li>Business allocation priority (priority ASC, normalized_code ASC) is strictly separated from DB lock ordering.</li>
 *   <li>Missing balance rows represent zero stock; no placeholder rows are auto-created.</li>
 *   <li>Shared mutable availability map prevents double-allocation across duplicate-product order lines.</li>
 *   <li>All-or-nothing atomicity: failure leaves zero traces in reservation, allocation, balance, or ledger tables.</li>
 * </ul>
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final JdbcReservationOperations jdbcReservationOperations;
    private final ReservationRepository reservationRepository;
    private final ReservationAllocationRepository reservationAllocationRepository;
    private final OrderItemRepository orderItemRepository;
    private final IdempotencyService idempotencyService;
    private final PlatformTransactionManager transactionManager;
    private final long defaultTtlMinutes;
    private final AuditLogService auditLogService;

    @Autowired
    public ReservationService(
            JdbcReservationOperations jdbcReservationOperations,
            ReservationRepository reservationRepository,
            ReservationAllocationRepository reservationAllocationRepository,
            OrderItemRepository orderItemRepository,
            IdempotencyService idempotencyService,
            PlatformTransactionManager transactionManager,
            ReservationProperties reservationProperties,
            AuditLogService auditLogService
    ) {
        this.jdbcReservationOperations = Objects.requireNonNull(jdbcReservationOperations, "jdbcReservationOperations must not be null");
        this.reservationRepository = Objects.requireNonNull(reservationRepository, "reservationRepository must not be null");
        this.reservationAllocationRepository = Objects.requireNonNull(reservationAllocationRepository, "reservationAllocationRepository must not be null");
        this.orderItemRepository = Objects.requireNonNull(orderItemRepository, "orderItemRepository must not be null");
        this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        Objects.requireNonNull(reservationProperties, "reservationProperties must not be null");
        if (reservationProperties.getDefaultTtlMinutes() <= 0) {
            throw new IllegalArgumentException("stockguard.reservation.default-ttl-minutes must be strictly greater than zero");
        }
        this.defaultTtlMinutes = reservationProperties.getDefaultTtlMinutes();
        this.auditLogService = Objects.requireNonNull(auditLogService, "auditLogService must not be null");
    }

    public ReservationService(
            JdbcReservationOperations jdbcReservationOperations,
            ReservationRepository reservationRepository,
            ReservationAllocationRepository reservationAllocationRepository,
            OrderItemRepository orderItemRepository,
            IdempotencyService idempotencyService,
            PlatformTransactionManager transactionManager,
            long defaultTtlMinutes,
            AuditLogService auditLogService
    ) {
        this.jdbcReservationOperations = Objects.requireNonNull(jdbcReservationOperations, "jdbcReservationOperations must not be null");
        this.reservationRepository = Objects.requireNonNull(reservationRepository, "reservationRepository must not be null");
        this.reservationAllocationRepository = Objects.requireNonNull(reservationAllocationRepository, "reservationAllocationRepository must not be null");
        this.orderItemRepository = Objects.requireNonNull(orderItemRepository, "orderItemRepository must not be null");
        this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        if (defaultTtlMinutes <= 0) {
            throw new IllegalArgumentException("stockguard.reservation.default-ttl-minutes must be strictly greater than zero");
        }
        this.defaultTtlMinutes = defaultTtlMinutes;
        this.auditLogService = Objects.requireNonNull(auditLogService, "auditLogService must not be null");
    }

    public ReservationResponse getReservation(UUID organizationId, UUID reservationId) {
        Reservation reservation = reservationRepository.findByIdAndOrganizationId(reservationId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found: " + reservationId));
        List<ReservationAllocation> allocations = reservationAllocationRepository
                .findAllByOrganizationIdAndReservationIdOrderByCreatedAtAsc(organizationId, reservationId);
        List<ReservationAllocationResponse> allocationResponses = allocations.stream()
                .map(ReservationAllocationResponse::fromDomain)
                .toList();
        return ReservationResponse.fromDomain(reservation, allocationResponses);
    }

    public ReservationResponse getReservationByOrderId(UUID organizationId, UUID orderId) {
        Reservation reservation = reservationRepository.findByOrganizationIdAndOrderId(organizationId, orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Reservation not found for order: " + orderId));
        List<ReservationAllocation> allocations = reservationAllocationRepository
                .findAllByOrganizationIdAndReservationIdOrderByCreatedAtAsc(organizationId, reservation.getId());
        List<ReservationAllocationResponse> allocationResponses = allocations.stream()
                .map(ReservationAllocationResponse::fromDomain)
                .toList();
        return ReservationResponse.fromDomain(reservation, allocationResponses);
    }

    /**
     * Executes atomic multi-warehouse inventory reservation with fresh-transaction retry
     * for transient concurrency failures.
     */
    public ReservationResponse createReservation(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId,
            String idempotencyKey
    ) {
        if (orderId == null) {
            throw new InvalidOperationException("Order ID must not be null");
        }

        int maxAttempts = 3;
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return executeReservationInTransaction(organizationId, actorUserId, orderId, idempotencyKey);
            } catch (Exception ex) {
                if (attempt < maxAttempts && TransferService.isTransientLockFailure(ex)) {
                    log.warn("Transient lock failure on reservation attempt {}/{}: {}. Retrying in fresh transaction...",
                            attempt, maxAttempts, ex.getMessage());
                    try {
                        Thread.sleep(25L * attempt + (long) (Math.random() * 25));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Reservation interrupted during retry backoff", ie);
                    }
                } else {
                    throw ex;
                }
            }
        }
    }

    private ReservationResponse executeReservationInTransaction(
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
                "ORDER_RESERVATION",
                idempotencyKey,
                new CreateReservationRequest(orderId),
                ReservationResponse.class,
                200, // HTTP 200 OK
                () -> executeReservationLogic(organizationId, actorUserId, orderId)
        ));
    }

    private ReservationResponse executeReservationLogic(
            UUID organizationId,
            UUID actorUserId,
            UUID orderId
    ) {
        // 1. Lock order row FOR UPDATE and revalidate
        LockedOrderInfo order = jdbcReservationOperations.lockOrder(organizationId, orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + orderId));

        if (order.status() != OrderStatus.PENDING) {
            throw new InvalidOrderStateException("Order must be in PENDING status to reserve inventory, but is " + order.status());
        }

        List<OrderItem> orderItems = orderItemRepository.findAllByOrganizationIdAndOrderIdOrderByLineNumberAsc(organizationId, orderId);
        if (orderItems.isEmpty()) {
            throw new InvalidOperationException("Order has no line items to reserve");
        }

        // 2. Lock reservation row FOR UPDATE and revalidate no reservation exists
        Optional<LockedReservationInfo> existingRes = jdbcReservationOperations.lockReservationForOrder(organizationId, orderId);
        if (existingRes.isPresent()) {
            throw new InvalidOrderStateException("Order already has a reservation with status: " + existingRes.get().status());
        }

        // 3. Lock warehouse rows using FOR SHARE, sorted by warehouse ID ascending
        List<LockedWarehouseInfo> allWarehouses = jdbcReservationOperations.lockAndGetWarehouses(organizationId);
        List<LockedWarehouseInfo> activeWarehouses = allWarehouses.stream()
                .filter(LockedWarehouseInfo::active)
                .toList();

        if (activeWarehouses.isEmpty()) {
            throw new InsufficientStockException("No active warehouses available for reservation");
        }

        // Business allocation priority: priority ASC, normalized_code ASC
        List<LockedWarehouseInfo> allocationWarehouses = new ArrayList<>(activeWarehouses);
        allocationWarehouses.sort(
                Comparator.comparingInt(LockedWarehouseInfo::priority)
                        .thenComparing(LockedWarehouseInfo::normalizedCode)
        );

        // 4. Lock product rows using FOR SHARE, sorted by product ID ascending
        List<UUID> distinctProductIds = orderItems.stream()
                .map(OrderItem::getProductId)
                .distinct()
                .sorted()
                .toList();

        List<LockedProductInfo> lockedProducts = jdbcReservationOperations.lockAndGetProducts(organizationId, distinctProductIds);
        Map<UUID, LockedProductInfo> productMap = new HashMap<>();
        for (LockedProductInfo p : lockedProducts) {
            productMap.put(p.id(), p);
        }

        for (UUID pid : distinctProductIds) {
            LockedProductInfo p = productMap.get(pid);
            if (p == null) {
                throw new ResourceNotFoundException("Product not found: " + pid);
            }
            if (!p.active()) {
                throw new InvalidOperationException("Product is inactive: " + p.sku());
            }
        }

        // 5. Lock inventory balance rows using FOR UPDATE, sorted by product_id ASC, warehouse_id ASC
        // Missing rows represent zero stock and are NOT auto-created
        List<InventoryBalance> lockedBalances = jdbcReservationOperations.lockAndGetExistingBalances(organizationId, distinctProductIds);
        Map<ProductWarehouseKey, InventoryBalance> balanceMap = new HashMap<>();
        for (InventoryBalance b : lockedBalances) {
            balanceMap.put(new ProductWarehouseKey(b.getProductId(), b.getWarehouseId()), b);
        }

        // Initialize shared mutable availability map across all warehouses and products
        Map<ProductWarehouseKey, Long> availableStock = new HashMap<>();
        for (UUID pid : distinctProductIds) {
            for (LockedWarehouseInfo wh : activeWarehouses) {
                ProductWarehouseKey key = new ProductWarehouseKey(pid, wh.id());
                InventoryBalance b = balanceMap.get(key);
                long avail = (b != null) ? Math.max(0L, b.getOnHand() - b.getReserved()) : 0L;
                availableStock.put(key, avail);
            }
        }

        // 6. Multi-warehouse deterministic allocation algorithm
        List<PlannedAllocation> plannedAllocations = new ArrayList<>();

        for (OrderItem item : orderItems) {
            UUID orderItemId = item.getId();
            UUID productId = item.getProductId();
            long requestedQty = item.getQuantity();

            // Preference: single warehouse that can fulfill the entire line
            LockedWarehouseInfo singleWh = null;
            for (LockedWarehouseInfo wh : allocationWarehouses) {
                ProductWarehouseKey key = new ProductWarehouseKey(productId, wh.id());
                long currentAvail = availableStock.getOrDefault(key, 0L);
                if (currentAvail >= requestedQty) {
                    singleWh = wh;
                    break;
                }
            }

            if (singleWh != null) {
                ProductWarehouseKey key = new ProductWarehouseKey(productId, singleWh.id());
                long currentAvail = availableStock.get(key);
                availableStock.put(key, currentAvail - requestedQty);
                plannedAllocations.add(new PlannedAllocation(orderItemId, productId, singleWh.id(), requestedQty));
            } else {
                // Split line across eligible warehouses in priority order
                long remainingNeeded = requestedQty;
                for (LockedWarehouseInfo wh : allocationWarehouses) {
                    if (remainingNeeded == 0) {
                        break;
                    }
                    ProductWarehouseKey key = new ProductWarehouseKey(productId, wh.id());
                    long currentAvail = availableStock.getOrDefault(key, 0L);
                    if (currentAvail > 0) {
                        long allocQty = Math.min(remainingNeeded, currentAvail);
                        availableStock.put(key, currentAvail - allocQty);
                        remainingNeeded -= allocQty;
                        plannedAllocations.add(new PlannedAllocation(orderItemId, productId, wh.id(), allocQty));
                    }
                }

                if (remainingNeeded > 0) {
                    throw new InsufficientStockException(
                            "Insufficient stock to reserve order item " + item.getId() +
                            " (SKU: " + item.getSkuSnapshot() + "). Requested: " + requestedQty +
                            ", unfulfilled: " + remainingNeeded
                    );
                }
            }
        }

        // Verify total allocated for each line equals requested
        for (OrderItem item : orderItems) {
            long totalAlloc = plannedAllocations.stream()
                    .filter(a -> a.orderItemId().equals(item.getId()))
                    .mapToLong(PlannedAllocation::quantity)
                    .sum();
            if (totalAlloc != item.getQuantity()) {
                throw new IllegalStateException("Allocation quantity mismatch for order item " + item.getId() +
                        ": requested=" + item.getQuantity() + ", allocated=" + totalAlloc);
            }
        }

        // 7. Atomic database operations
        UUID reservationId = UUID.randomUUID();

        // A. Insert reservation header (PostgreSQL calculates expires_at using its database clock via NOW() + (? * INTERVAL '1 minute'))
        InsertedReservationHeader header = jdbcReservationOperations.insertReservation(
                reservationId, organizationId, orderId, defaultTtlMinutes
        );
        Instant dbCreatedAt = header.createdAt();
        Instant dbExpiresAt = header.expiresAt();

        // B. Insert reservation allocations
        List<ReservationAllocationResponse> allocationResponses = new ArrayList<>(plannedAllocations.size());
        for (PlannedAllocation a : plannedAllocations) {
            UUID allocId = UUID.randomUUID();
            jdbcReservationOperations.insertReservationAllocation(
                    allocId, organizationId, reservationId, orderId, a.orderItemId(), a.productId(), a.warehouseId(), a.quantity()
            );
            allocationResponses.add(new ReservationAllocationResponse(
                    allocId, a.orderItemId(), a.productId(), a.warehouseId(), a.quantity(), dbCreatedAt
            ));
        }

        // C. Insert inventory transaction header
        UUID txId = UUID.randomUUID();
        jdbcReservationOperations.insertInventoryTransaction(
                txId, organizationId, "RESERVATION", "ORDER", orderId, "Inventory hold for order " + orderId, actorUserId
        );

        // D. Aggregate reserved deltas per product-warehouse and update balances and ledgers in canonical order
        Map<ProductWarehouseKey, Long> totalReservedByPw = new LinkedHashMap<>();
        for (PlannedAllocation a : plannedAllocations) {
            ProductWarehouseKey key = new ProductWarehouseKey(a.productId(), a.warehouseId());
            totalReservedByPw.merge(key, a.quantity(), Long::sum);
        }

        List<ProductWarehouseKey> sortedKeys = new ArrayList<>(totalReservedByPw.keySet());
        Collections.sort(sortedKeys);

        int entryNumber = 1;
        for (ProductWarehouseKey key : sortedKeys) {
            long reservedDelta = totalReservedByPw.get(key);
            InventoryBalance balance = balanceMap.get(key);
            if (balance == null) {
                throw new IllegalStateException("Unexpected missing balance for allocated product " + key.productId());
            }

            jdbcReservationOperations.updateInventoryBalanceReserved(balance.getId(), organizationId, reservedDelta);
            jdbcReservationOperations.insertInventoryLedgerEntry(
                    UUID.randomUUID(), organizationId, txId, entryNumber++, key.productId(), key.warehouseId(), 0L, reservedDelta
            );
        }

        long totalReservedQty = plannedAllocations.stream()
                .mapToLong(PlannedAllocation::quantity)
                .sum();
        auditLogService.logReservationCreated(
                organizationId,
                actorUserId,
                reservationId,
                orderId,
                plannedAllocations.size(),
                totalReservedQty,
                dbExpiresAt,
                txId
        );

        return new ReservationResponse(
                reservationId,
                organizationId,
                orderId,
                ReservationStatus.ACTIVE,
                dbExpiresAt,
                allocationResponses,
                dbCreatedAt,
                header.updatedAt()
        );
    }

    private record PlannedAllocation(
            UUID orderItemId,
            UUID productId,
            UUID warehouseId,
            long quantity
    ) {}
}
