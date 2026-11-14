package com.stockguard.organization.service;

import com.stockguard.audit.service.AuditLogService;
import com.stockguard.organization.domain.Organization;
import com.stockguard.organization.domain.OrganizationMembership;
import com.stockguard.organization.domain.Role;
import com.stockguard.organization.dto.OrganizationMemberResponse;
import com.stockguard.organization.exception.CannotDemoteLastAdminException;
import com.stockguard.organization.repository.OrganizationMembershipRepository;
import com.stockguard.organization.repository.OrganizationRepository;
import com.stockguard.security.context.OrganizationContext;
import com.stockguard.security.context.OrganizationContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.repository.query.FluentQuery;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class OrganizationMembershipServiceTest {

    private UUID orgId;
    private UUID adminUserId;
    private UUID secondAdminUserId;
    private UUID operatorUserId;

    private StubOrganizationRepository orgRepo;
    private StubOrganizationMembershipRepository membershipRepo;
    private AuditLogService auditLogService;
    private OrganizationMembershipService service;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        adminUserId = UUID.randomUUID();
        secondAdminUserId = UUID.randomUUID();
        operatorUserId = UUID.randomUUID();

        orgRepo = new StubOrganizationRepository();
        membershipRepo = new StubOrganizationMembershipRepository();

        orgRepo.existingOrgIds.add(orgId);

        auditLogService = mock(AuditLogService.class);
        service = new OrganizationMembershipService(orgRepo, membershipRepo, auditLogService);

        OrganizationContextHolder.setContext(new OrganizationContext(
                orgId, adminUserId, Role.ADMIN, Set.of("membership:manage")
        ));
    }

    @AfterEach
    void tearDown() {
        OrganizationContextHolder.clearContext();
    }

    @Test
    @DisplayName("getOrganizationMemberships returns all active memberships")
    void getOrganizationMembershipsReturnsActive() {
        OrganizationMembership m1 = OrganizationMembership.create(orgId, adminUserId, Role.ADMIN);
        OrganizationMembership m2 = OrganizationMembership.create(orgId, operatorUserId, Role.OPERATOR);
        membershipRepo.save(m1);
        membershipRepo.save(m2);

        List<OrganizationMemberResponse> result = service.getOrganizationMemberships(orgId);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(OrganizationMemberResponse::userId)
                .containsExactlyInAnyOrder(adminUserId, operatorUserId);
    }

    @Test
    @DisplayName("addMember creates new membership when none exists")
    void addMemberCreatesNew() {
        UUID newUserId = UUID.randomUUID();
        OrganizationMemberResponse response = service.addMember(orgId, newUserId, Role.MANAGER);

        assertThat(response).isNotNull();
        assertThat(response.organizationId()).isEqualTo(orgId);
        assertThat(response.userId()).isEqualTo(newUserId);
        assertThat(response.role()).isEqualTo(Role.MANAGER);
        assertThat(response.active()).isTrue();
    }

    @Test
    @DisplayName("addMember throws IllegalStateException if user is already an active member")
    void addMemberFailsIfAlreadyActive() {
        OrganizationMembership m = OrganizationMembership.create(orgId, adminUserId, Role.ADMIN);
        membershipRepo.save(m);

        assertThatThrownBy(() -> service.addMember(orgId, adminUserId, Role.MANAGER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already an active member");
    }

    @Test
    @DisplayName("addMember reactivates membership if user previously had an inactive membership")
    void addMemberReactivatesInactive() {
        OrganizationMembership m = new OrganizationMembership(
                UUID.randomUUID(), orgId, operatorUserId, Role.VIEWER, false, Instant.now(), Instant.now()
        );
        membershipRepo.save(m);

        OrganizationMemberResponse response = service.addMember(orgId, operatorUserId, Role.OPERATOR);

        assertThat(response.active()).isTrue();
        assertThat(response.role()).isEqualTo(Role.OPERATOR);
    }

    @Test
    @DisplayName("updateMemberRole succeeds when changing non-admin role")
    void updateMemberRoleNonAdminSucceeds() {
        OrganizationMembership m = OrganizationMembership.create(orgId, operatorUserId, Role.OPERATOR);
        membershipRepo.save(m);

        OrganizationMemberResponse response = service.updateMemberRole(orgId, operatorUserId, Role.MANAGER);

        assertThat(response.role()).isEqualTo(Role.MANAGER);
    }

    @Test
    @DisplayName("updateMemberRole succeeds when demoting admin if another active admin exists")
    void demoteAdminSucceedsWhenMultipleAdminsExist() {
        OrganizationMembership admin1 = OrganizationMembership.create(orgId, adminUserId, Role.ADMIN);
        OrganizationMembership admin2 = OrganizationMembership.create(orgId, secondAdminUserId, Role.ADMIN);
        membershipRepo.save(admin1);
        membershipRepo.save(admin2);

        OrganizationMemberResponse response = service.updateMemberRole(orgId, adminUserId, Role.MANAGER);

        assertThat(response.role()).isEqualTo(Role.MANAGER);
    }

    @Test
    @DisplayName("updateMemberRole fails with CannotDemoteLastAdminException when demoting the sole remaining admin")
    void demoteAdminFailsWhenSoleAdmin() {
        OrganizationMembership admin1 = OrganizationMembership.create(orgId, adminUserId, Role.ADMIN);
        membershipRepo.save(admin1);

        assertThatThrownBy(() -> service.updateMemberRole(orgId, adminUserId, Role.MANAGER))
                .isInstanceOf(CannotDemoteLastAdminException.class)
                .hasMessageContaining("Cannot demote or remove the sole remaining administrator");
    }

    @Test
    @DisplayName("deactivateMember fails with CannotDemoteLastAdminException when deactivating sole remaining admin")
    void deactivateAdminFailsWhenSoleAdmin() {
        OrganizationMembership admin1 = OrganizationMembership.create(orgId, adminUserId, Role.ADMIN);
        membershipRepo.save(admin1);

        assertThatThrownBy(() -> service.deactivateMember(orgId, adminUserId))
                .isInstanceOf(CannotDemoteLastAdminException.class)
                .hasMessageContaining("Cannot demote or remove the sole remaining administrator");
    }

    @Test
    @DisplayName("deactivateMember succeeds when multiple active admins exist")
    void deactivateAdminSucceedsWhenMultipleAdminsExist() {
        OrganizationMembership admin1 = OrganizationMembership.create(orgId, adminUserId, Role.ADMIN);
        OrganizationMembership admin2 = OrganizationMembership.create(orgId, secondAdminUserId, Role.ADMIN);
        membershipRepo.save(admin1);
        membershipRepo.save(admin2);

        service.deactivateMember(orgId, adminUserId);

        Optional<OrganizationMembership> updated = membershipRepo.findByOrganizationIdAndUserId(orgId, adminUserId);
        assertThat(updated).isPresent();
        assertThat(updated.get().isActive()).isFalse();
    }

    @Test
    @DisplayName("deactivateMember succeeds for non-admin regardless of admin count")
    void deactivateNonAdminSucceeds() {
        OrganizationMembership admin1 = OrganizationMembership.create(orgId, adminUserId, Role.ADMIN);
        OrganizationMembership op = OrganizationMembership.create(orgId, operatorUserId, Role.OPERATOR);
        membershipRepo.save(admin1);
        membershipRepo.save(op);

        service.deactivateMember(orgId, operatorUserId);

        Optional<OrganizationMembership> updated = membershipRepo.findByOrganizationIdAndUserId(orgId, operatorUserId);
        assertThat(updated).isPresent();
        assertThat(updated.get().isActive()).isFalse();
    }

    @Test
    @DisplayName("addMember throws NullPointerException when actorUserId is null")
    void addMemberRejectsNullActor() {
        UUID newUserId = UUID.randomUUID();
        assertThatThrownBy(() -> service.addMember(orgId, null, newUserId, Role.MANAGER))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        assertThat(membershipRepo.findByOrganizationIdAndUserId(orgId, newUserId)).isEmpty();
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("updateMemberRole throws NullPointerException when actorUserId is null")
    void updateMemberRoleRejectsNullActor() {
        OrganizationMembership m = OrganizationMembership.create(orgId, operatorUserId, Role.OPERATOR);
        membershipRepo.save(m);

        assertThatThrownBy(() -> service.updateMemberRole(orgId, null, operatorUserId, Role.MANAGER))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        OrganizationMembership saved = membershipRepo.findByOrganizationIdAndUserId(orgId, operatorUserId).orElseThrow();
        assertThat(saved.getRole()).isEqualTo(Role.OPERATOR);
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("deactivateMember throws NullPointerException when actorUserId is null")
    void deactivateMemberRejectsNullActor() {
        OrganizationMembership m = OrganizationMembership.create(orgId, operatorUserId, Role.OPERATOR);
        membershipRepo.save(m);

        assertThatThrownBy(() -> service.deactivateMember(orgId, null, operatorUserId))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("actorUserId must not be null");

        OrganizationMembership saved = membershipRepo.findByOrganizationIdAndUserId(orgId, operatorUserId).orElseThrow();
        assertThat(saved.isActive()).isTrue();
        verifyNoInteractions(auditLogService);
    }

    @Test
    @DisplayName("addMember via 3-arg overload throws IllegalStateException when context is missing")
    void addMemberFailsWhenContextMissing() {
        OrganizationContextHolder.clearContext();
        UUID newUserId = UUID.randomUUID();
        assertThatThrownBy(() -> service.addMember(orgId, newUserId, Role.MANAGER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Authenticated actor user ID is required");

        assertThat(membershipRepo.findByOrganizationIdAndUserId(orgId, newUserId)).isEmpty();
        verifyNoInteractions(auditLogService);
    }

    // --- Lightweight Repository Stubs ---

    static class StubOrganizationRepository implements OrganizationRepository {
        final Set<UUID> existingOrgIds = new HashSet<>();

        @Override
        public Optional<UUID> lockOrganization(UUID id) {
            return existingOrgIds.contains(id) ? Optional.of(id) : Optional.empty();
        }

        @Override
        public Optional<Organization> findByName(String name) { return Optional.empty(); }
        @Override
        public boolean existsByName(String name) { return false; }
        @Override
        public void flush() {}
        @Override
        public <S extends Organization> S saveAndFlush(S entity) { return entity; }
        @Override
        public <S extends Organization> List<S> saveAllAndFlush(Iterable<S> entities) { return List.of(); }
        @Override
        public void deleteAllInBatch(Iterable<Organization> entities) {}
        @Override
        public void deleteAllByIdInBatch(Iterable<UUID> uuids) {}
        @Override
        public void deleteAllInBatch() {}
        @Override
        public Organization getOne(UUID uuid) { return null; }
        @Override
        public Organization getById(UUID uuid) { return null; }
        @Override
        public Organization getReferenceById(UUID uuid) { return null; }
        @Override
        public <S extends Organization> Optional<S> findOne(Example<S> example) { return Optional.empty(); }
        @Override
        public <S extends Organization> List<S> findAll(Example<S> example) { return List.of(); }
        @Override
        public <S extends Organization> List<S> findAll(Example<S> example, Sort sort) { return List.of(); }
        @Override
        public <S extends Organization> Page<S> findAll(Example<S> example, Pageable pageable) { return Page.empty(); }
        @Override
        public <S extends Organization> long count(Example<S> example) { return 0; }
        @Override
        public <S extends Organization> boolean exists(Example<S> example) { return false; }
        @Override
        public <S extends Organization, R> R findBy(Example<S> example, Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
        @Override
        public <S extends Organization> S save(S entity) { return entity; }
        @Override
        public <S extends Organization> List<S> saveAll(Iterable<S> entities) { return List.of(); }
        @Override
        public Optional<Organization> findById(UUID uuid) { return Optional.empty(); }
        @Override
        public boolean existsById(UUID uuid) { return existingOrgIds.contains(uuid); }
        @Override
        public List<Organization> findAll() { return List.of(); }
        @Override
        public List<Organization> findAllById(Iterable<UUID> uuids) { return List.of(); }
        @Override
        public long count() { return existingOrgIds.size(); }
        @Override
        public void deleteById(UUID uuid) {}
        @Override
        public void delete(Organization entity) {}
        @Override
        public void deleteAllById(Iterable<? extends UUID> uuids) {}
        @Override
        public void deleteAll(Iterable<? extends Organization> entities) {}
        @Override
        public void deleteAll() {}
        @Override
        public List<Organization> findAll(Sort sort) { return List.of(); }
        @Override
        public Page<Organization> findAll(Pageable pageable) { return Page.empty(); }
    }

    static class StubOrganizationMembershipRepository implements OrganizationMembershipRepository {
        final Map<String, OrganizationMembership> storage = new HashMap<>();

        private String key(UUID orgId, UUID userId) {
            return orgId + ":" + userId;
        }

        @Override
        public Optional<OrganizationMembership> findByOrganizationIdAndUserId(UUID organizationId, UUID userId) {
            return Optional.ofNullable(storage.get(key(organizationId, userId)));
        }

        @Override
        public List<OrganizationMembership> findAllByUserIdAndActiveTrue(UUID userId) {
            return storage.values().stream()
                    .filter(m -> m.getUserId().equals(userId) && m.isActive())
                    .toList();
        }

        @Override
        public List<OrganizationMembership> findAllByOrganizationIdAndActiveTrue(UUID organizationId) {
            return storage.values().stream()
                    .filter(m -> m.getOrganizationId().equals(organizationId) && m.isActive())
                    .toList();
        }

        @Override
        public long countByOrganizationIdAndRoleAndActiveTrue(UUID organizationId, Role role) {
            return storage.values().stream()
                    .filter(m -> m.getOrganizationId().equals(organizationId) && m.getRole() == role && m.isActive())
                    .count();
        }

        @Override
        public boolean existsByOrganizationIdAndUserIdAndActiveTrue(UUID organizationId, UUID userId) {
            OrganizationMembership m = storage.get(key(organizationId, userId));
            return m != null && m.isActive();
        }

        @Override
        public <S extends OrganizationMembership> S save(S entity) {
            storage.put(key(entity.getOrganizationId(), entity.getUserId()), entity);
            return entity;
        }

        @Override
        public void flush() {}
        @Override
        public <S extends OrganizationMembership> S saveAndFlush(S entity) { return save(entity); }
        @Override
        public <S extends OrganizationMembership> List<S> saveAllAndFlush(Iterable<S> entities) { return List.of(); }
        @Override
        public void deleteAllInBatch(Iterable<OrganizationMembership> entities) {}
        @Override
        public void deleteAllByIdInBatch(Iterable<UUID> uuids) {}
        @Override
        public void deleteAllInBatch() {}
        @Override
        public OrganizationMembership getOne(UUID uuid) { return null; }
        @Override
        public OrganizationMembership getById(UUID uuid) { return null; }
        @Override
        public OrganizationMembership getReferenceById(UUID uuid) { return null; }
        @Override
        public <S extends OrganizationMembership> Optional<S> findOne(Example<S> example) { return Optional.empty(); }
        @Override
        public <S extends OrganizationMembership> List<S> findAll(Example<S> example) { return List.of(); }
        @Override
        public <S extends OrganizationMembership> List<S> findAll(Example<S> example, Sort sort) { return List.of(); }
        @Override
        public <S extends OrganizationMembership> Page<S> findAll(Example<S> example, Pageable pageable) { return Page.empty(); }
        @Override
        public <S extends OrganizationMembership> long count(Example<S> example) { return 0; }
        @Override
        public <S extends OrganizationMembership> boolean exists(Example<S> example) { return false; }
        @Override
        public <S extends OrganizationMembership, R> R findBy(Example<S> example, Function<FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { return null; }
        @Override
        public <S extends OrganizationMembership> List<S> saveAll(Iterable<S> entities) { return List.of(); }
        @Override
        public Optional<OrganizationMembership> findById(UUID uuid) { return Optional.empty(); }
        @Override
        public boolean existsById(UUID uuid) { return false; }
        @Override
        public List<OrganizationMembership> findAll() { return List.copyOf(storage.values()); }
        @Override
        public List<OrganizationMembership> findAllById(Iterable<UUID> uuids) { return List.of(); }
        @Override
        public long count() { return storage.size(); }
        @Override
        public void deleteById(UUID uuid) {}
        @Override
        public void delete(OrganizationMembership entity) {}
        @Override
        public void deleteAllById(Iterable<? extends UUID> uuids) {}
        @Override
        public void deleteAll(Iterable<? extends OrganizationMembership> entities) {}
        @Override
        public void deleteAll() {}
        @Override
        public List<OrganizationMembership> findAll(Sort sort) { return List.of(); }
        @Override
        public Page<OrganizationMembership> findAll(Pageable pageable) { return Page.empty(); }
    }
}
