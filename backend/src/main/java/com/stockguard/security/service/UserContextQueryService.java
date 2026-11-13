package com.stockguard.security.service;

import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.domain.RolePermissions;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.security.domain.User;
import com.stockguard.security.dto.*;
import com.stockguard.security.exception.AuthenticationFailedException;
import com.stockguard.security.repository.UserRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Service providing user-scoped context, organization membership discovery,
 * and organization switching per ADR-010 and ADR-011.
 */
@Service
@Profile("!test")
@Transactional(readOnly = true)
public class UserContextQueryService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMembershipRepository membershipRepository;

    public UserContextQueryService(
            UserRepository userRepository,
            OrganizationRepository organizationRepository,
            OrganizationMembershipRepository membershipRepository
    ) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.membershipRepository = membershipRepository;
    }

    /**
     * Discovers all active organizations where the user holds an active membership.
     */
    public List<OrganizationSummaryDto> getUserAvailableOrganizations(UUID userId) {
        if (userId == null) {
            return Collections.emptyList();
        }

        List<OrganizationMembership> memberships = membershipRepository.findAllByUserIdAndActiveTrue(userId);
        if (memberships.isEmpty()) {
            return Collections.emptyList();
        }

        List<OrganizationSummaryDto> result = new ArrayList<>();
        for (OrganizationMembership m : memberships) {
            Optional<Organization> orgOpt = organizationRepository.findById(m.getOrganizationId());
            if (orgOpt.isPresent()) {
                result.add(new OrganizationSummaryDto(
                        orgOpt.get().getId(),
                        orgOpt.get().getName(),
                        m.getRole()
                ));
            }
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Resolves authenticated user profile and all active organization memberships for {@code GET /api/auth/me}.
     */
    public UserMeResponse getUserMe(UUID userId) {
        if (userId == null) {
            throw new AuthenticationFailedException("User not authenticated");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationFailedException("User account not found"));

        if (!user.isActive()) {
            throw new AuthenticationFailedException("User account is inactive");
        }

        List<OrganizationMembership> memberships = membershipRepository.findAllByUserIdAndActiveTrue(userId);
        List<OrganizationMembershipSummaryDto> membershipSummaries = new ArrayList<>();

        for (OrganizationMembership m : memberships) {
            Optional<Organization> orgOpt = organizationRepository.findById(m.getOrganizationId());
            if (orgOpt.isPresent()) {
                membershipSummaries.add(new OrganizationMembershipSummaryDto(
                        orgOpt.get().getId(),
                        orgOpt.get().getName(),
                        m.getRole()
                ));
            }
        }

        String fullName = deriveFullName(user.getEmail());

        return new UserMeResponse(
                user.getId(),
                user.getEmail(),
                fullName,
                user.isActive(),
                Collections.unmodifiableList(membershipSummaries)
        );
    }

    /**
     * Validates and executes an organization switch for {@code POST /api/auth/switch-org}.
     * <p>
     * Does NOT mutate {@code auth_sessions} or alter the JWT; returns the validated context
     * so that the client tab can declare its context via {@code X-Organization-Id}.
     */
    public SwitchOrgResponse switchOrganization(UUID userId, UUID targetOrgId) {
        if (userId == null) {
            throw new AuthenticationFailedException("User not authenticated");
        }
        if (targetOrgId == null) {
            throw new IllegalArgumentException("Target organization ID must not be null");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new AuthenticationFailedException("User account not found"));

        if (!user.isActive()) {
            throw new AuthenticationFailedException("User account is inactive");
        }

        OrganizationMembership membership = membershipRepository.findByOrganizationIdAndUserId(targetOrgId, userId)
                .orElseThrow(() -> new AccessDeniedException("User has no membership in organization: " + targetOrgId));

        if (!membership.isActive()) {
            throw new AccessDeniedException("User membership in organization is inactive: " + targetOrgId);
        }

        Organization organization = organizationRepository.findById(targetOrgId)
                .orElseThrow(() -> new AccessDeniedException("Organization not found: " + targetOrgId));

        Role role = membership.getRole();
        Set<String> permissions = RolePermissions.getPermissionAuthoritiesForRole(role);

        return new SwitchOrgResponse(
                organization.getId(),
                organization.getName(),
                role,
                permissions
        );
    }

    private String deriveFullName(String email) {
        if (email == null || !email.contains("@")) {
            return email;
        }
        String local = email.substring(0, email.indexOf('@')).replace('.', ' ').replace('_', ' ');
        return Character.toUpperCase(local.charAt(0)) + local.substring(1);
    }
}
