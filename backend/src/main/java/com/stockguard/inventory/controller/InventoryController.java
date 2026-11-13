package com.stockguard.inventory.controller;

import com.stockguard.inventory.dto.*;
import com.stockguard.inventory.service.InventoryReconciliationService;
import com.stockguard.inventory.service.InventoryService;
import com.stockguard.security.context.OrganizationContext;
import com.stockguard.security.context.OrganizationContextHolder;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/inventory")
@Profile("!test")
public class InventoryController {

    private final InventoryService inventoryService;
    private final InventoryReconciliationService reconciliationService;

    public InventoryController(
            InventoryService inventoryService,
            InventoryReconciliationService reconciliationService
    ) {
        this.inventoryService = inventoryService;
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/balances")
    @PreAuthorize("hasAuthority('inventory:view')")
    public ResponseEntity<List<InventoryBalanceResponse>> getBalances(
            @RequestParam(name = "productId", required = false) UUID productId,
            @RequestParam(name = "warehouseId", required = false) UUID warehouseId
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        List<InventoryBalanceResponse> balances = inventoryService.getBalances(
                context.organizationId(),
                productId,
                warehouseId
        );
        return ResponseEntity.ok(balances);
    }

    @GetMapping("/balances/{productId}/{warehouseId}")
    @PreAuthorize("hasAuthority('inventory:view')")
    public ResponseEntity<InventoryBalanceResponse> getBalance(
            @PathVariable UUID productId,
            @PathVariable UUID warehouseId
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        InventoryBalanceResponse balance = inventoryService.getBalance(
                context.organizationId(),
                productId,
                warehouseId
        );
        return ResponseEntity.ok(balance);
    }

    @PostMapping("/opening-balances")
    @PreAuthorize("hasAuthority('inventory:receive')")
    public ResponseEntity<InventoryTransactionResponse> recordOpeningBalance(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OpeningBalanceRequest request
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        InventoryTransactionResponse response = inventoryService.recordOpeningBalance(
                context.organizationId(),
                context.userId(),
                request,
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/receipts")
    @PreAuthorize("hasAuthority('inventory:receive')")
    public ResponseEntity<InventoryTransactionResponse> receiveStock(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ReceiveStockRequest request
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        InventoryTransactionResponse response = inventoryService.receiveStock(
                context.organizationId(),
                context.userId(),
                request,
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/adjustments")
    @PreAuthorize("hasAuthority('inventory:adjust')")
    public ResponseEntity<InventoryTransactionResponse> adjustStock(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody AdjustStockRequest request
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        InventoryTransactionResponse response = inventoryService.adjustStock(
                context.organizationId(),
                context.userId(),
                request,
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/transactions")
    @PreAuthorize("hasAuthority('inventory:view')")
    public ResponseEntity<List<InventoryTransactionResponse>> listTransactions(
            @RequestParam(name = "limit", defaultValue = "50") int limit
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        List<InventoryTransactionResponse> txs = inventoryService.listTransactions(context.organizationId(), limit);
        return ResponseEntity.ok(txs);
    }

    @GetMapping("/transactions/{transactionId}")
    @PreAuthorize("hasAuthority('inventory:view')")
    public ResponseEntity<InventoryTransactionResponse> getTransaction(@PathVariable UUID transactionId) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        InventoryTransactionResponse tx = inventoryService.getTransaction(context.organizationId(), transactionId);
        return ResponseEntity.ok(tx);
    }

    @GetMapping("/reconciliation")
    @PreAuthorize("hasAuthority('inventory:reconcile')")
    public ResponseEntity<ReconciliationReport> reconcile() {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        ReconciliationReport report = reconciliationService.reconcile(context.organizationId());
        return ResponseEntity.ok(report);
    }
}
