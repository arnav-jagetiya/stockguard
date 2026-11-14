package com.stockguard.audit.repository;

import com.stockguard.audit.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Optional<AuditLog> findByIdAndOrganizationId(UUID id, UUID organizationId);
}
