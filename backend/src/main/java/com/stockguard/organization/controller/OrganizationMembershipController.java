package com.stockguard.organization.controller;

import com.stockguard.organization.dto.AddMemberRequest;
import com.stockguard.organization.dto.OrganizationMemberResponse;
import com.stockguard.organization.dto.UpdateMemberRoleRequest;
import com.stockguard.organization.exception.CannotDemoteLastAdminException;
import com.stockguard.organization.service.OrganizationMembershipService;
import com.stockguard.security.context.OrganizationContext;
import com.stockguard.security.context.OrganizationContextHolder;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Controller exposing administrative membership management endpoints per ADR-011.
 * <p>
 * Enforces method-level authorization using granular permissions ({@code membership:view},
 * {@code membership:manage}) and resolves tenant boundary from {@link OrganizationContextHolder}.
 */
@RestController
@RequestMapping("/api/organizations/current")
@Profile("!test")
public class OrganizationMembershipController {

    private final OrganizationMembershipService membershipService;

    public OrganizationMembershipController(OrganizationMembershipService membershipService) {
        this.membershipService = membershipService;
    }

    @GetMapping("/memberships")
    @PreAuthorize("hasAuthority('membership:view')")
    public ResponseEntity<List<OrganizationMemberResponse>> getMemberships() {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        List<OrganizationMemberResponse> members = membershipService.getOrganizationMemberships(context.organizationId());
        return ResponseEntity.ok(members);
    }

    @PostMapping("/members")
    @PreAuthorize("hasAuthority('membership:manage')")
    public ResponseEntity<OrganizationMemberResponse> addMember(
            @Valid @RequestBody AddMemberRequest request
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        OrganizationMemberResponse member = membershipService.addMember(
                context.organizationId(),
                request.userId(),
                request.role()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(member);
    }

    @PutMapping("/members/{targetUserId}/role")
    @PreAuthorize("hasAuthority('membership:manage')")
    public ResponseEntity<OrganizationMemberResponse> updateMemberRole(
            @PathVariable UUID targetUserId,
            @Valid @RequestBody UpdateMemberRoleRequest request
    ) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        OrganizationMemberResponse updated = membershipService.updateMemberRole(
                context.organizationId(),
                targetUserId,
                request.role()
        );
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/members/{targetUserId}")
    @PreAuthorize("hasAuthority('membership:manage')")
    public ResponseEntity<?> deactivateMember(@PathVariable UUID targetUserId) {
        OrganizationContext context = OrganizationContextHolder.requireContext();
        membershipService.deactivateMember(context.organizationId(), targetUserId);
        return ResponseEntity.ok(Map.of("message", "Member successfully deactivated"));
    }

    @ExceptionHandler(CannotDemoteLastAdminException.class)
    public ResponseEntity<Map<String, Object>> handleCannotDemoteLastAdmin(CannotDemoteLastAdminException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "Conflict",
                "status", 409,
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "Bad Request",
                "status", 400,
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "Conflict",
                "status", 409,
                "message", ex.getMessage()
        ));
    }
}
