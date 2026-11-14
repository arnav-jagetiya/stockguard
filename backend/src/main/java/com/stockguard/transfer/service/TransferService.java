package com.stockguard.transfer.service;

import com.stockguard.audit.service.AuditLogService;
import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.domain.ReferenceType;
import com.stockguard.inventory.domain.TransactionType;
import com.stockguard.inventory.repository.JdbcInventoryOperations;
import com.stockguard.product.domain.Product;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.shared.exception.InsufficientStockException;
import com.stockguard.shared.exception.InvalidOperationException;
import com.stockguard.shared.exception.ResourceNotFoundException;
import com.stockguard.shared.idempotency.IdempotencyService;
import com.stockguard.transfer.dto.*;
import com.stockguard.warehouse.domain.Warehouse;
import com.stockguard.warehouse.repository.WarehouseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

/**
 * Service managing atomic, multi-item inter-warehouse inventory transfers.
 * <p>
 * Canonical locking establishes deterministic acquisition order for the specified
 * warehouse, product and inventory-balance rows. It substantially reduces deadlock risk
 * within those lock domains, but does not guarantee system-wide deadlock freedom.
 * Eligible transient database failures are handled through fresh-transaction retries.
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final JdbcInventoryOperations jdbcInventoryOperations;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final IdempotencyService idempotencyService;
    private final PlatformTransactionManager transactionManager;
    private final AuditLogService auditLogService;

    @Autowired
    public TransferService(
            JdbcInventoryOperations jdbcInventoryOperations,
            ProductRepository productRepository,
            WarehouseRepository warehouseRepository,
            IdempotencyService idempotencyService,
            PlatformTransactionManager transactionManager,
            AuditLogService auditLogService
    ) {
        this.jdbcInventoryOperations = Objects.requireNonNull(jdbcInventoryOperations, "jdbcInventoryOperations must not be null");
        this.productRepository = Objects.requireNonNull(productRepository, "productRepository must not be null");
        this.warehouseRepository = Objects.requireNonNull(warehouseRepository, "warehouseRepository must not be null");
        this.idempotencyService = Objects.requireNonNull(idempotencyService, "idempotencyService must not be null");
        this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService, "auditLogService must not be null");
    }

    public TransferResponse getTransfer(UUID organizationId, UUID transferId) {
        return jdbcInventoryOperations.findTransferById(organizationId, transferId)
                .orElseThrow(() -> new ResourceNotFoundException("Transfer not found: " + transferId));
    }

    public List<TransferResponse> listTransfers(UUID organizationId, UUID warehouseId, int limit) {
        int boundedLimit = Math.max(1, Math.min(limit, 200));
        return jdbcInventoryOperations.listRecentTransfers(organizationId, warehouseId, boundedLimit);
    }

    /**
     * Executes an atomic multi-item inter-warehouse inventory transfer with retry on transient lock failures.
     */
    public TransferResponse transferStock(
            UUID organizationId,
            UUID actorUserId,
            CreateTransferRequest request,
            String idempotencyKey
    ) {
        // 1. Fast non-locked domain validations
        validateRequest(organizationId, request);

        // 2. Retry loop for eligible transient PostgreSQL concurrency failures
        int maxAttempts = 3;
        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return executeTransferInTransaction(organizationId, actorUserId, request, idempotencyKey);
            } catch (Exception ex) {
                if (attempt < maxAttempts && isTransientLockFailure(ex)) {
                    log.warn("Transient lock failure on transfer attempt {}/{}: {}. Retrying in fresh transaction...",
                            attempt, maxAttempts, ex.getMessage());
                    try {
                        Thread.sleep(25L * attempt + (long) (Math.random() * 25));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Transfer interrupted during retry backoff", ie);
                    }
                } else {
                    throw ex;
                }
            }
        }
    }

    private TransferResponse executeTransferInTransaction(
            UUID organizationId,
            UUID actorUserId,
            CreateTransferRequest request,
            String idempotencyKey
    ) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        return template.execute(status -> idempotencyService.execute(
                organizationId,
                actorUserId,
                "INVENTORY_TRANSFER",
                idempotencyKey,
                request,
                TransferResponse.class,
                201, // HTTP 201 Created
                () -> executeTransferLogic(organizationId, actorUserId, request)
        ));
    }

    private TransferResponse executeTransferLogic(
            UUID organizationId,
            UUID actorUserId,
            CreateTransferRequest request
    ) {
        // 1. Authoritative in-transaction validation: acquire FOR SHARE locks on warehouses in canonical order (id ASC)
        List<JdbcInventoryOperations.LockedWarehouseInfo> lockedWarehouses = jdbcInventoryOperations.lockAndGetWarehouses(
                organizationId, request.sourceWarehouseId(), request.destinationWarehouseId()
        );
        Map<UUID, JdbcInventoryOperations.LockedWarehouseInfo> whMap = new HashMap<>();
        for (JdbcInventoryOperations.LockedWarehouseInfo w : lockedWarehouses) {
            whMap.put(w.id(), w);
        }

        JdbcInventoryOperations.LockedWarehouseInfo srcWh = whMap.get(request.sourceWarehouseId());
        if (srcWh == null) {
            throw new ResourceNotFoundException("Source warehouse not found: " + request.sourceWarehouseId());
        }
        if (!srcWh.active()) {
            throw new InvalidOperationException("Source warehouse is inactive: " + srcWh.code());
        }

        JdbcInventoryOperations.LockedWarehouseInfo dstWh = whMap.get(request.destinationWarehouseId());
        if (dstWh == null) {
            throw new ResourceNotFoundException("Destination warehouse not found: " + request.destinationWarehouseId());
        }
        if (!dstWh.active()) {
            throw new InvalidOperationException("Destination warehouse is inactive: " + dstWh.code());
        }

        // 2. Authoritative in-transaction validation: acquire FOR SHARE locks on products in canonical order (id ASC)
        List<UUID> productIds = request.items().stream().map(TransferItemRequest::productId).toList();
        List<JdbcInventoryOperations.LockedProductInfo> lockedProducts = jdbcInventoryOperations.lockAndGetProducts(
                organizationId, productIds
        );
        Map<UUID, JdbcInventoryOperations.LockedProductInfo> prodMap = new HashMap<>();
        for (JdbcInventoryOperations.LockedProductInfo p : lockedProducts) {
            prodMap.put(p.id(), p);
        }

        for (UUID pid : productIds) {
            JdbcInventoryOperations.LockedProductInfo p = prodMap.get(pid);
            if (p == null) {
                throw new ResourceNotFoundException("Product not found: " + pid);
            }
            if (!p.active()) {
                throw new InvalidOperationException("Product is inactive: " + p.sku());
            }
        }

        // 3. Ensure all source and destination balance rows exist prior to balance locking
        for (TransferItemRequest item : request.items()) {
            jdbcInventoryOperations.ensureBalanceRowExists(organizationId, item.productId(), request.sourceWarehouseId());
            jdbcInventoryOperations.ensureBalanceRowExists(organizationId, item.productId(), request.destinationWarehouseId());
        }

        // 4. Acquire row-level locks in strictly canonical order: product_id ASC, warehouse_id ASC (ADR-003)
        List<JdbcInventoryOperations.ProductWarehouseKey> keysToLock = new ArrayList<>();
        for (TransferItemRequest item : request.items()) {
            keysToLock.add(new JdbcInventoryOperations.ProductWarehouseKey(item.productId(), request.sourceWarehouseId()));
            keysToLock.add(new JdbcInventoryOperations.ProductWarehouseKey(item.productId(), request.destinationWarehouseId()));
        }

        List<InventoryBalance> lockedBalances = jdbcInventoryOperations.lockAndGetBalances(organizationId, keysToLock);
        Map<JdbcInventoryOperations.ProductWarehouseKey, InventoryBalance> balanceMap = new HashMap<>();
        for (InventoryBalance b : lockedBalances) {
            balanceMap.put(new JdbcInventoryOperations.ProductWarehouseKey(b.getProductId(), b.getWarehouseId()), b);
        }

        // 5. Verify source available stock (never consume reserved stock)
        for (TransferItemRequest item : request.items()) {
            InventoryBalance sourceBalance = balanceMap.get(
                    new JdbcInventoryOperations.ProductWarehouseKey(item.productId(), request.sourceWarehouseId())
            );
            long availableStock = sourceBalance.getOnHand() - sourceBalance.getReserved();
            if (availableStock < item.quantity()) {
                throw new InsufficientStockException(
                        "Insufficient available stock for product " + item.productId() +
                                " at source warehouse " + request.sourceWarehouseId() +
                                ": requested " + item.quantity() + ", available " + availableStock +
                                " (onHand=" + sourceBalance.getOnHand() + ", reserved=" + sourceBalance.getReserved() + ")"
                );
            }
        }

        // 4. Update source and destination balances atomically
        for (TransferItemRequest item : request.items()) {
            InventoryBalance sourceBalance = balanceMap.get(
                    new JdbcInventoryOperations.ProductWarehouseKey(item.productId(), request.sourceWarehouseId())
            );
            InventoryBalance destBalance = balanceMap.get(
                    new JdbcInventoryOperations.ProductWarehouseKey(item.productId(), request.destinationWarehouseId())
            );

            jdbcInventoryOperations.updateBalance(sourceBalance.getId(), organizationId, -item.quantity(), 0L);
            jdbcInventoryOperations.updateBalance(destBalance.getId(), organizationId, +item.quantity(), 0L);
        }

        // 5. Create transfer header
        UUID transferId = UUID.randomUUID();
        jdbcInventoryOperations.insertTransfer(
                transferId,
                organizationId,
                request.sourceWarehouseId(),
                request.destinationWarehouseId(),
                request.notes(),
                actorUserId
        );

        // 6. Create transfer items (sorted deterministically by product ID)
        List<TransferItemRequest> sortedItems = new ArrayList<>(request.items());
        sortedItems.sort(Comparator.comparing(TransferItemRequest::productId));

        List<TransferItemResponse> createdItemResponses = new ArrayList<>(sortedItems.size());
        List<TransferItemEntityInfo> createdItemsInfo = new ArrayList<>(sortedItems.size());
        Instant now = Instant.now();

        for (TransferItemRequest item : sortedItems) {
            UUID itemId = UUID.randomUUID();
            jdbcInventoryOperations.insertTransferItem(
                    itemId,
                    organizationId,
                    transferId,
                    item.productId(),
                    item.quantity()
            );
            createdItemResponses.add(new TransferItemResponse(itemId, item.productId(), item.quantity(), now));
            createdItemsInfo.add(new TransferItemEntityInfo(itemId, item.productId(), item.quantity()));
        }

        // 7. Create TRANSFER inventory transaction header
        UUID transactionId = UUID.randomUUID();
        jdbcInventoryOperations.insertTransaction(
                transactionId,
                organizationId,
                TransactionType.TRANSFER.name(),
                ReferenceType.TRANSFER.name(),
                transferId,
                request.notes(),
                actorUserId
        );

        // 8. Create deterministic sequential ledger entries (2N entries)
        List<CreatedLedgerEntryInfo> createdLedgers = new ArrayList<>(createdItemsInfo.size() * 2);
        for (int i = 0; i < createdItemsInfo.size(); i++) {
            TransferItemEntityInfo item = createdItemsInfo.get(i);
            int entryNumberOut = (2 * i) + 1;
            int entryNumberIn = (2 * i) + 2;

            UUID entryIdOut = UUID.randomUUID();
            jdbcInventoryOperations.insertTransferLedgerEntry(
                    entryIdOut,
                    organizationId,
                    transactionId,
                    entryNumberOut,
                    item.productId(),
                    request.sourceWarehouseId(),
                    -item.quantity(),
                    0L,
                    item.id(),
                    "OUT"
            );
            createdLedgers.add(new CreatedLedgerEntryInfo(
                    entryIdOut, entryNumberOut, item.id(), item.productId(),
                    request.sourceWarehouseId(), -item.quantity(), 0L, "OUT"
            ));

            UUID entryIdIn = UUID.randomUUID();
            jdbcInventoryOperations.insertTransferLedgerEntry(
                    entryIdIn,
                    organizationId,
                    transactionId,
                    entryNumberIn,
                    item.productId(),
                    request.destinationWarehouseId(),
                    +item.quantity(),
                    0L,
                    item.id(),
                    "IN"
            );
            createdLedgers.add(new CreatedLedgerEntryInfo(
                    entryIdIn, entryNumberIn, item.id(), item.productId(),
                    request.destinationWarehouseId(), +item.quantity(), 0L, "IN"
            ));
        }

        // 9. Pre-commit pair completeness & relationship validation
        verifyTransferIntegrity(
                request.sourceWarehouseId(),
                request.destinationWarehouseId(),
                createdItemsInfo,
                createdLedgers
        );

        // 10. Audit logging within the same physical transaction
        List<Map<String, Object>> itemSummaries = new ArrayList<>(sortedItems.size());
        for (TransferItemRequest item : sortedItems) {
            itemSummaries.add(Map.of("productId", item.productId().toString(), "quantity", item.quantity()));
        }
        auditLogService.logTransferExecuted(
                organizationId,
                actorUserId,
                transferId,
                request.sourceWarehouseId(),
                request.destinationWarehouseId(),
                sortedItems.size(),
                transactionId,
                itemSummaries
        );

        return new TransferResponse(
                transferId,
                organizationId,
                request.sourceWarehouseId(),
                request.destinationWarehouseId(),
                request.notes(),
                actorUserId,
                now,
                now,
                transactionId,
                createdItemResponses
        );
    }

    private void validateRequest(UUID organizationId, CreateTransferRequest request) {
        if (request == null) {
            throw new InvalidOperationException("Transfer request cannot be null");
        }
        if (request.sourceWarehouseId() == null || request.destinationWarehouseId() == null) {
            throw new InvalidOperationException("Source and destination warehouses are required");
        }
        if (request.sourceWarehouseId().equals(request.destinationWarehouseId())) {
            throw new InvalidOperationException("Source and destination warehouses cannot be the same");
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new InvalidOperationException("Transfer items list cannot be empty");
        }

        Set<UUID> seenProducts = new HashSet<>();
        for (TransferItemRequest item : request.items()) {
            if (item.productId() == null) {
                throw new InvalidOperationException("Product ID cannot be null");
            }
            if (item.quantity() == null || item.quantity() <= 0) {
                throw new InvalidOperationException("Quantity must be greater than zero for product: " + item.productId());
            }
            if (!seenProducts.add(item.productId())) {
                throw new InvalidOperationException("Duplicate product ID in transfer request: " + item.productId());
            }
        }

        // Validate active source and destination warehouses
        Warehouse sourceWh = warehouseRepository.findByIdAndOrganizationId(request.sourceWarehouseId(), organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Source warehouse not found: " + request.sourceWarehouseId()));
        if (!sourceWh.isActive()) {
            throw new InvalidOperationException("Source warehouse is inactive: " + sourceWh.getCode());
        }

        Warehouse destWh = warehouseRepository.findByIdAndOrganizationId(request.destinationWarehouseId(), organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Destination warehouse not found: " + request.destinationWarehouseId()));
        if (!destWh.isActive()) {
            throw new InvalidOperationException("Destination warehouse is inactive: " + destWh.getCode());
        }

        // Validate active products
        for (TransferItemRequest item : request.items()) {
            Product product = productRepository.findByIdAndOrganizationId(item.productId(), organizationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + item.productId()));
            if (!product.isActive()) {
                throw new InvalidOperationException("Product is inactive: " + product.getSku());
            }
        }
    }

    private void verifyTransferIntegrity(
            UUID sourceWarehouseId,
            UUID destinationWarehouseId,
            List<TransferItemEntityInfo> items,
            List<CreatedLedgerEntryInfo> ledgers
    ) {
        if (ledgers.size() != items.size() * 2) {
            throw new IllegalStateException(
                    "Transfer ledger count mismatch: expected " + (items.size() * 2) + " but got " + ledgers.size()
            );
        }

        long netOnHandDelta = 0;
        long netReservedDelta = 0;

        for (TransferItemEntityInfo item : items) {
            long outCount = ledgers.stream()
                    .filter(l -> l.transferItemId().equals(item.id()) && "OUT".equals(l.direction()))
                    .count();
            long inCount = ledgers.stream()
                    .filter(l -> l.transferItemId().equals(item.id()) && "IN".equals(l.direction()))
                    .count();

            if (outCount != 1 || inCount != 1) {
                throw new IllegalStateException(
                        "Pair completeness violated for transfer item " + item.id() +
                                ": OUT count=" + outCount + ", IN count=" + inCount
                );
            }

            CreatedLedgerEntryInfo outEntry = ledgers.stream()
                    .filter(l -> l.transferItemId().equals(item.id()) && "OUT".equals(l.direction()))
                    .findFirst().orElseThrow();
            CreatedLedgerEntryInfo inEntry = ledgers.stream()
                    .filter(l -> l.transferItemId().equals(item.id()) && "IN".equals(l.direction()))
                    .findFirst().orElseThrow();

            if (!outEntry.warehouseId().equals(sourceWarehouseId)) {
                throw new IllegalStateException("OUT ledger warehouse mismatch for item " + item.id());
            }
            if (!inEntry.warehouseId().equals(destinationWarehouseId)) {
                throw new IllegalStateException("IN ledger warehouse mismatch for item " + item.id());
            }
            if (!outEntry.productId().equals(item.productId()) || !inEntry.productId().equals(item.productId())) {
                throw new IllegalStateException("Product mismatch in ledger entries for item " + item.id());
            }
            if (outEntry.onHandDelta() != -item.quantity() || inEntry.onHandDelta() != item.quantity()) {
                throw new IllegalStateException("Quantity delta mismatch in ledger entries for item " + item.id());
            }
            if (outEntry.reservedDelta() != 0L || inEntry.reservedDelta() != 0L) {
                throw new IllegalStateException("Reserved delta must be 0 for transfer entries");
            }
        }

        for (CreatedLedgerEntryInfo ledger : ledgers) {
            netOnHandDelta += ledger.onHandDelta();
            netReservedDelta += ledger.reservedDelta();
        }

        if (netOnHandDelta != 0 || netReservedDelta != 0) {
            throw new IllegalStateException(
                    "Stock conservation invariant violated: netOnHandDelta=" + netOnHandDelta +
                            ", netReservedDelta=" + netReservedDelta
            );
        }
    }

    public static boolean isTransientLockFailure(Throwable ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof org.springframework.dao.CannotAcquireLockException
                    || current instanceof org.springframework.dao.DeadlockLoserDataAccessException
                    || current instanceof org.springframework.dao.PessimisticLockingFailureException
                    || current instanceof org.springframework.dao.CannotSerializeTransactionException) {
                return true;
            }
            if (current instanceof SQLException sqlEx) {
                String state = sqlEx.getSQLState();
                if ("40P01".equals(state) || "40001".equals(state) || "55P03".equals(state)) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private record TransferItemEntityInfo(UUID id, UUID productId, long quantity) {}

    private record CreatedLedgerEntryInfo(
            UUID id,
            int entryNumber,
            UUID transferItemId,
            UUID productId,
            UUID warehouseId,
            long onHandDelta,
            long reservedDelta,
            String direction
    ) {}
}
