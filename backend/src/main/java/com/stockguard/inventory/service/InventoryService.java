package com.stockguard.inventory.service;

import com.stockguard.inventory.domain.InventoryBalance;
import com.stockguard.inventory.domain.ReferenceType;
import com.stockguard.inventory.domain.TransactionType;
import com.stockguard.inventory.dto.*;
import com.stockguard.inventory.repository.InventoryBalanceRepository;
import com.stockguard.inventory.repository.JdbcInventoryOperations;
import com.stockguard.product.domain.Product;
import com.stockguard.product.repository.ProductRepository;
import com.stockguard.shared.exception.InsufficientStockException;
import com.stockguard.shared.exception.InvalidOperationException;
import com.stockguard.shared.exception.ResourceNotFoundException;
import com.stockguard.shared.idempotency.IdempotencyService;
import com.stockguard.warehouse.domain.Warehouse;
import com.stockguard.warehouse.repository.WarehouseRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Profile("!test")
@Transactional
public class InventoryService {

    private final JdbcInventoryOperations jdbcInventoryOperations;
    private final InventoryBalanceRepository balanceRepository;
    private final ProductRepository productRepository;
    private final WarehouseRepository warehouseRepository;
    private final IdempotencyService idempotencyService;

    public InventoryService(
            JdbcInventoryOperations jdbcInventoryOperations,
            InventoryBalanceRepository balanceRepository,
            ProductRepository productRepository,
            WarehouseRepository warehouseRepository,
            IdempotencyService idempotencyService
    ) {
        this.jdbcInventoryOperations = jdbcInventoryOperations;
        this.balanceRepository = balanceRepository;
        this.productRepository = productRepository;
        this.warehouseRepository = warehouseRepository;
        this.idempotencyService = idempotencyService;
    }

    @Transactional(readOnly = true)
    public List<InventoryBalanceResponse> getBalances(UUID organizationId, UUID productId, UUID warehouseId) {
        if (productId != null && warehouseId != null) {
            return balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(organizationId, productId, warehouseId)
                    .map(InventoryBalanceResponse::fromDomain)
                    .map(List::of)
                    .orElseGet(List::of);
        } else if (productId != null) {
            return balanceRepository.findAllByOrganizationIdAndProductId(organizationId, productId).stream()
                    .map(InventoryBalanceResponse::fromDomain)
                    .toList();
        } else if (warehouseId != null) {
            return balanceRepository.findAllByOrganizationIdAndWarehouseId(organizationId, warehouseId).stream()
                    .map(InventoryBalanceResponse::fromDomain)
                    .toList();
        } else {
            return balanceRepository.findAllByOrganizationId(organizationId).stream()
                    .map(InventoryBalanceResponse::fromDomain)
                    .toList();
        }
    }

    @Transactional(readOnly = true)
    public InventoryBalanceResponse getBalance(UUID organizationId, UUID productId, UUID warehouseId) {
        return balanceRepository.findByOrganizationIdAndProductIdAndWarehouseId(organizationId, productId, warehouseId)
                .map(InventoryBalanceResponse::fromDomain)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Inventory balance not found for product " + productId + " and warehouse " + warehouseId
                ));
    }

    public InventoryTransactionResponse recordOpeningBalance(
            UUID organizationId,
            UUID actorUserId,
            OpeningBalanceRequest request,
            String idempotencyKey
    ) {
        validateProductAndWarehouseActive(organizationId, request.productId(), request.warehouseId());

        return idempotencyService.execute(
                organizationId,
                actorUserId,
                "INVENTORY_OPENING_BALANCE",
                idempotencyKey,
                request,
                InventoryTransactionResponse.class,
                () -> {
                    // 1. Acquire row lock in canonical order
                    InventoryBalance balance = jdbcInventoryOperations.lockAndGetBalance(
                            organizationId,
                            request.productId(),
                            request.warehouseId()
                    );

                    // 2. An OPENING_BALANCE transaction may exist only once per (organization, product, warehouse).
                    // Historical verification checks transactions and ledger entries under row lock, rejecting a second opening balance
                    // even if subsequent stock movements or adjustments have brought on-hand and reserved back to zero.
                    if (jdbcInventoryOperations.hasOpeningBalanceBeenRecorded(organizationId, request.productId(), request.warehouseId())) {
                        throw new InvalidOperationException(
                                "Opening balance has already been established for product " + request.productId() +
                                        " at warehouse " + request.warehouseId() + ". Use RECEIPT or ADJUSTMENT instead."
                        );
                    }
                    if (balance.getOnHand() > 0 || balance.getReserved() > 0) {
                        throw new InvalidOperationException(
                                "Cannot record opening balance when stock is already present for product " + request.productId() +
                                        " at warehouse " + request.warehouseId() + ". Use RECEIPT or ADJUSTMENT instead."
                        );
                    }

                    // 3. Atomically update balance and insert transaction & ledger entry
                    UUID txId = UUID.randomUUID();
                    UUID entryId = UUID.randomUUID();

                    jdbcInventoryOperations.updateBalance(balance.getId(), organizationId, request.quantity(), 0L);
                    jdbcInventoryOperations.insertTransaction(
                            txId,
                            organizationId,
                            TransactionType.OPENING_BALANCE.name(),
                            null,
                            null,
                            request.notes(),
                            actorUserId
                    );
                    jdbcInventoryOperations.insertLedgerEntry(
                            entryId,
                            organizationId,
                            txId,
                            1,
                            request.productId(),
                            request.warehouseId(),
                            request.quantity(),
                            0L
                    );

                    LedgerEntryDto entryDto = new LedgerEntryDto(
                            entryId,
                            1,
                            request.productId(),
                            request.warehouseId(),
                            request.quantity(),
                            0L,
                            Instant.now()
                    );

                    return new InventoryTransactionResponse(
                            txId,
                            organizationId,
                            TransactionType.OPENING_BALANCE.name(),
                            null,
                            null,
                            request.notes(),
                            actorUserId,
                            Instant.now(),
                            List.of(entryDto)
                    );
                }
        );
    }

    public InventoryTransactionResponse receiveStock(
            UUID organizationId,
            UUID actorUserId,
            ReceiveStockRequest request,
            String idempotencyKey
    ) {
        validateProductAndWarehouseActive(organizationId, request.productId(), request.warehouseId());

        return idempotencyService.execute(
                organizationId,
                actorUserId,
                "INVENTORY_RECEIPT",
                idempotencyKey,
                request,
                InventoryTransactionResponse.class,
                () -> {
                    // 1. Acquire row lock in canonical order
                    InventoryBalance balance = jdbcInventoryOperations.lockAndGetBalance(
                            organizationId,
                            request.productId(),
                            request.warehouseId()
                    );

                    // 2. Atomically update balance and insert transaction & ledger entry
                    UUID txId = UUID.randomUUID();
                    UUID entryId = UUID.randomUUID();
                    String refType = request.referenceId() != null ? ReferenceType.MANUAL.name() : null;

                    jdbcInventoryOperations.updateBalance(balance.getId(), organizationId, request.quantity(), 0L);
                    jdbcInventoryOperations.insertTransaction(
                            txId,
                            organizationId,
                            TransactionType.RECEIPT.name(),
                            refType,
                            request.referenceId(),
                            request.notes(),
                            actorUserId
                    );
                    jdbcInventoryOperations.insertLedgerEntry(
                            entryId,
                            organizationId,
                            txId,
                            1,
                            request.productId(),
                            request.warehouseId(),
                            request.quantity(),
                            0L
                    );

                    LedgerEntryDto entryDto = new LedgerEntryDto(
                            entryId,
                            1,
                            request.productId(),
                            request.warehouseId(),
                            request.quantity(),
                            0L,
                            Instant.now()
                    );

                    return new InventoryTransactionResponse(
                            txId,
                            organizationId,
                            TransactionType.RECEIPT.name(),
                            refType,
                            request.referenceId(),
                            request.notes(),
                            actorUserId,
                            Instant.now(),
                            List.of(entryDto)
                    );
                }
        );
    }

    public InventoryTransactionResponse adjustStock(
            UUID organizationId,
            UUID actorUserId,
            AdjustStockRequest request,
            String idempotencyKey
    ) {
        validateProductAndWarehouseActive(organizationId, request.productId(), request.warehouseId());

        return idempotencyService.execute(
                organizationId,
                actorUserId,
                "INVENTORY_ADJUSTMENT",
                idempotencyKey,
                request,
                InventoryTransactionResponse.class,
                () -> {
                    // 1. Acquire row lock in canonical order
                    InventoryBalance balance = jdbcInventoryOperations.lockAndGetBalance(
                            organizationId,
                            request.productId(),
                            request.warehouseId()
                    );

                    // 2. Validate adjustment boundary invariants under row lock
                    long newOnHand = balance.getOnHand() + request.delta();
                    if (newOnHand < 0) {
                        throw new InsufficientStockException(
                                "Stock adjustment of " + request.delta() + " would result in negative on-hand inventory (" + newOnHand + ")"
                        );
                    }
                    if (newOnHand < balance.getReserved()) {
                        throw new InsufficientStockException(
                                "Stock adjustment of " + request.delta() + " would reduce on-hand inventory (" + newOnHand +
                                        ") below current reserved holds (" + balance.getReserved() + ")"
                        );
                    }

                    // 3. Atomically update balance and insert transaction & ledger entry
                    UUID txId = UUID.randomUUID();
                    UUID entryId = UUID.randomUUID();
                    String refType = request.referenceId() != null ? ReferenceType.MANUAL.name() : null;

                    jdbcInventoryOperations.updateBalance(balance.getId(), organizationId, request.delta(), 0L);
                    jdbcInventoryOperations.insertTransaction(
                            txId,
                            organizationId,
                            TransactionType.ADJUSTMENT.name(),
                            refType,
                            request.referenceId(),
                            request.reason(),
                            actorUserId
                    );
                    jdbcInventoryOperations.insertLedgerEntry(
                            entryId,
                            organizationId,
                            txId,
                            1,
                            request.productId(),
                            request.warehouseId(),
                            request.delta(),
                            0L
                    );

                    LedgerEntryDto entryDto = new LedgerEntryDto(
                            entryId,
                            1,
                            request.productId(),
                            request.warehouseId(),
                            request.delta(),
                            0L,
                            Instant.now()
                    );

                    return new InventoryTransactionResponse(
                            txId,
                            organizationId,
                            TransactionType.ADJUSTMENT.name(),
                            refType,
                            request.referenceId(),
                            request.reason(),
                            actorUserId,
                            Instant.now(),
                            List.of(entryDto)
                    );
                }
        );
    }

    @Transactional(readOnly = true)
    public List<InventoryTransactionResponse> listTransactions(UUID organizationId, int limit) {
        return jdbcInventoryOperations.listRecentTransactions(organizationId, limit);
    }

    @Transactional(readOnly = true)
    public InventoryTransactionResponse getTransaction(UUID organizationId, UUID transactionId) {
        return jdbcInventoryOperations.getTransaction(organizationId, transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Inventory transaction not found: " + transactionId));
    }

    private void validateProductAndWarehouseActive(UUID organizationId, UUID productId, UUID warehouseId) {
        Product product = productRepository.findByIdAndOrganizationId(productId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + productId));

        if (!product.isActive()) {
            throw new InvalidOperationException("Product is inactive: " + product.getSku());
        }

        Warehouse warehouse = warehouseRepository.findByIdAndOrganizationId(warehouseId, organizationId)
                .orElseThrow(() -> new ResourceNotFoundException("Warehouse not found: " + warehouseId));

        if (!warehouse.isActive()) {
            throw new InvalidOperationException("Warehouse is inactive: " + warehouse.getCode());
        }
    }
}
