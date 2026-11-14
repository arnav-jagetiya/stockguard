package com.stockguard.organization.service;

import com.stockguard.audit.service.AuditLogService;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.dto.OrganizationMemberResponse;
import com.stockguard.organization.exception.CannotDemoteLastAdminException;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.security.context.OrganizationContext;
import com.stockguard.security.context.OrganizationContextHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing organization memberships and enforcing concurrency-safe
 * last-admin protection per ADR-011.
 */
@Service
@Profile("!test")
public class OrganizationMembershipService {

    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;
    private final AuditLogService auditLogService;

    @Autowired
    public OrganizationMembershipService(
            OrganizationRepository organizationRepository,
            OrganizationMembershipRepository membershipRepository,
            AuditLogService auditLogService
    ) {
        this.organizationRepository = Objects.requireNonNull(organizationRepository, "organizationRepository must not be null");
        this.membershipRepository = Objects.requireNonNull(membershipRepository, "membershipRepository must not be null");
        this.auditLogService = Objects.requireNonNull(auditLogService, "auditLogService must not be null");
    }

    @Transactional(readOnly = true)
    public List<OrganizationMemberResponse> getOrganizationMemberships(UUID organizationId) {
        if (organizationId == null) {
            throw new IllegalArgumentException("Organization ID must not be null");
        }
        return membershipRepository.findAllByOrganizationIdAndActiveTrue(organizationId).stream()
                .map(this::mapToResponse)
                .toList();
    }

    @Transactional
    public OrganizationMemberResponse addMember(UUID organizationId, UUID actorUserId, UUID targetUserId, Role role) {
        Objects.requireNonNull(organizationId, "Organization ID must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for member addition");
        Objects.requireNonNull(targetUserId, "User ID must not be null");
        Objects.requireNonNull(role, "Role must not be null");

        // Lock organization row to serialize membership mutations
        organizationRepository.lockOrganization(organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + organizationId));

        Optional<OrganizationMembership> existingOpt =
                membershipRepository.findByOrganizationIdAndUserId(organizationId, targetUserId);

        OrganizationMembership membership;
        boolean reactivated = false;
        if (existingOpt.isPresent()) {
            membership = existingOpt.get();
            if (membership.isActive()) {
                throw new IllegalStateException("User is already an active member of organization: " + organizationId);
            }
            membership.setActive(true);
            membership.setRole(role);
            reactivated = true;
        } else {
            membership = OrganizationMembership.create(organizationId, targetUserId, role);
        }

        OrganizationMembership saved = membershipRepository.save(membership);

        auditLogService.logMemberAdded(organizationId, actorUserId, targetUserId, role.name(), reactivated);

        return mapToResponse(saved);
    }

    @Transactional
    public OrganizationMemberResponse addMember(UUID organizationId, UUID targetUserId, Role role) {
        UUID actorUserId = OrganizationContextHolder.getContext()
                .map(OrganizationContext::userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated actor user ID is required"));
        return addMember(organizationId, actorUserId, targetUserId, role);
    }

    @Transactional
    public OrganizationMemberResponse updateMemberRole(UUID organizationId, UUID actorUserId, UUID targetUserId, Role newRole) {
        Objects.requireNonNull(organizationId, "Organization ID must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for role update");
        Objects.requireNonNull(targetUserId, "User ID must not be null");
        Objects.requireNonNull(newRole, "Role must not be null");

        // Lock organization row to serialize membership mutations
        organizationRepository.lockOrganization(organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + organizationId));

        OrganizationMembership membership = membershipRepository.findByOrganizationIdAndUserId(organizationId, targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("Membership not found for user: " + targetUserId));

        Role oldRole = membership.getRole();
        if (oldRole == Role.ADMIN && newRole != Role.ADMIN && membership.isActive()) {
            long activeAdminCount = membershipRepository.countByOrganizationIdAndRoleAndActiveTrue(organizationId, Role.ADMIN);
            if (activeAdminCount <= 1) {
                throw new CannotDemoteLastAdminException(
                        "Cannot demote or remove the sole remaining administrator for the organization."
                );
            }
        }

        membership.setRole(newRole);
        OrganizationMembership saved = membershipRepository.save(membership);

        auditLogService.logMemberRoleUpdated(organizationId, actorUserId, targetUserId, oldRole.name(), newRole.name());

        return mapToResponse(saved);
    }

    @Transactional
    public OrganizationMemberResponse updateMemberRole(UUID organizationId, UUID targetUserId, Role newRole) {
        UUID actorUserId = OrganizationContextHolder.getContext()
                .map(OrganizationContext::userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated actor user ID is required"));
        return updateMemberRole(organizationId, actorUserId, targetUserId, newRole);
    }

    @Transactional
    public void deactivateMember(UUID organizationId, UUID actorUserId, UUID targetUserId) {
        Objects.requireNonNull(organizationId, "Organization ID must not be null");
        Objects.requireNonNull(actorUserId, "actorUserId must not be null for member deactivation");
        Objects.requireNonNull(targetUserId, "User ID must not be null");

        // Lock organization row to serialize membership mutations
        organizationRepository.lockOrganization(organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + organizationId));

        OrganizationMembership membership = membershipRepository.findByOrganizationIdAndUserId(organizationId, targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("Membership not found for user: " + targetUserId));

        if (membership.getRole() == Role.ADMIN && membership.isActive()) {
            long activeAdminCount = membershipRepository.countByOrganizationIdAndRoleAndActiveTrue(organizationId, Role.ADMIN);
            if (activeAdminCount <= 1) {
                throw new CannotDemoteLastAdminException(
                        "Cannot demote or remove the sole remaining administrator for the organization."
                );
            }
        }

        membership.setActive(false);
        membershipRepository.save(membership);

        auditLogService.logMemberDeactivated(organizationId, actorUserId, targetUserId);
    }

    @Transactional
    public void deactivateMember(UUID organizationId, UUID targetUserId) {
        UUID actorUserId = OrganizationContextHolder.getContext()
                .map(OrganizationContext::userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated actor user ID is required"));
        deactivateMember(organizationId, actorUserId, targetUserId);
    }

    private OrganizationMemberResponse mapToResponse(OrganizationMembership m) {
        return new OrganizationMemberResponse(
                m.getId(),
                m.getOrganizationId(),
                m.getUserId(),
                m.getRole(),
                m.isActive(),
                m.getCreatedAt(),
                m.getUpdatedAt()
        );
    }
}
