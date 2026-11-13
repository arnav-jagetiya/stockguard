package com.stockguard.transfer.controller;

import com.stockguard.security.context.OrganizationContext;
import com.stockguard.security.context.OrganizationContextHolder;
import com.stockguard.transfer.dto.CreateTransferRequest;
import com.stockguard.transfer.dto.TransferResponse;
import com.stockguard.transfer.service.TransferService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('transfer:execute')")
    public ResponseEntity<TransferResponse> executeTransfer(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        TransferResponse response = transferService.transferStock(
                context.organizationId(),
                context.userId(),
                request,
                idempotencyKey
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('transfer:view')")
    public ResponseEntity<List<TransferResponse>> listTransfers(
            @RequestParam(name = "warehouseId", required = false) UUID warehouseId,
            @RequestParam(name = "limit", required = false, defaultValue = "50") int limit
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        List<TransferResponse> transfers = transferService.listTransfers(
                context.organizationId(),
                warehouseId,
                limit
        );
        return ResponseEntity.ok(transfers);
    }

    @GetMapping("/{transferId}")
    @PreAuthorize("hasAuthority('transfer:view')")
    public ResponseEntity<TransferResponse> getTransfer(
            @PathVariable UUID transferId
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        TransferResponse transfer = transferService.getTransfer(
                context.organizationId(),
                transferId
        );
        return ResponseEntity.ok(transfer);
    }
}
