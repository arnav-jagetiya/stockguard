package com.stockguard.organization.repository;

import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrganizationMembershipRepository extends JpaRepository<OrganizationMembership, UUID> {

    Optional<OrganizationMembership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId);

    List<OrganizationMembership> findAllByUserIdAndActiveTrue(UUID userId);

    List<OrganizationMembership> findAllByOrganizationIdAndActiveTrue(UUID organizationId);

    long countByOrganizationIdAndRoleAndActiveTrue(UUID organizationId, Role role);

    boolean existsByOrganizationIdAndUserIdAndActiveTrue(UUID organizationId, UUID userId);
}
