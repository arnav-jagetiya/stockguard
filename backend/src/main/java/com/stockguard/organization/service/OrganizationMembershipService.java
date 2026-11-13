package com.stockguard.organization.service;

import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.dto.OrganizationMemberResponse;
import com.stockguard.organization.exception.CannotDemoteLastAdminException;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    public OrganizationMembershipService(
            OrganizationRepository organizationRepository,
            OrganizationMembershipRepository membershipRepository
    ) {
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
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
    public OrganizationMemberResponse addMember(UUID organizationId, UUID targetUserId, Role role) {
        if (organizationId == null || targetUserId == null || role == null) {
            throw new IllegalArgumentException("Organization ID, user ID, and role must not be null");
        }

        // Lock organization row to serialize membership mutations
        organizationRepository.lockOrganization(organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + organizationId));

        Optional<OrganizationMembership> existingOpt =
                membershipRepository.findByOrganizationIdAndUserId(organizationId, targetUserId);

        OrganizationMembership membership;
        if (existingOpt.isPresent()) {
            membership = existingOpt.get();
            if (membership.isActive()) {
                throw new IllegalStateException("User is already an active member of organization: " + organizationId);
            }
            membership.setActive(true);
            membership.setRole(role);
        } else {
            membership = OrganizationMembership.create(organizationId, targetUserId, role);
        }

        OrganizationMembership saved = membershipRepository.save(membership);
        return mapToResponse(saved);
    }

    @Transactional
    public OrganizationMemberResponse updateMemberRole(UUID organizationId, UUID targetUserId, Role newRole) {
        if (organizationId == null || targetUserId == null || newRole == null) {
            throw new IllegalArgumentException("Organization ID, user ID, and role must not be null");
        }

        // Lock organization row to serialize membership mutations
        organizationRepository.lockOrganization(organizationId)
                .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + organizationId));

        OrganizationMembership membership = membershipRepository.findByOrganizationIdAndUserId(organizationId, targetUserId)
                .orElseThrow(() -> new IllegalArgumentException("Membership not found for user: " + targetUserId));

        if (membership.getRole() == Role.ADMIN && newRole != Role.ADMIN && membership.isActive()) {
            long activeAdminCount = membershipRepository.countByOrganizationIdAndRoleAndActiveTrue(organizationId, Role.ADMIN);
            if (activeAdminCount <= 1) {
                throw new CannotDemoteLastAdminException(
                        "Cannot demote or remove the sole remaining administrator for the organization."
                );
            }
        }

        membership.setRole(newRole);
        OrganizationMembership saved = membershipRepository.save(membership);
        return mapToResponse(saved);
    }

    @Transactional
    public void deactivateMember(UUID organizationId, UUID targetUserId) {
        if (organizationId == null || targetUserId == null) {
            throw new IllegalArgumentException("Organization ID and user ID must not be null");
        }

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
