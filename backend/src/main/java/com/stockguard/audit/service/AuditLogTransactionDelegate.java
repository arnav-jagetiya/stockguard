package com.stockguard.audit.service;

import com.stockguard.audit.repository.JdbcAuditLogOperations;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Spring transaction delegate responsible for executing independent audit log writes
 * within a fresh transaction boundary ({@link Propagation#REQUIRES_NEW}).
 * <p>
 * This separate bean ensures invocations cross Spring AOP dynamic proxies, guaranteeing
 * that security audit events commit even if the caller's transaction subsequently aborts.
 */
@Component
public class AuditLogTransactionDelegate {

    private final JdbcAuditLogOperations jdbcAuditLogOperations;

    public AuditLogTransactionDelegate(JdbcAuditLogOperations jdbcAuditLogOperations) {
        this.jdbcAuditLogOperations = jdbcAuditLogOperations;
    }

    /**
     * Persists an audit log entry in an independent transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID writeInNewTransaction(
            UUID organizationId,
            UUID actorUserId,
            String action,
            String entityType,
            UUID entityId,
            Map<String, Object> beforeState,
            Map<String, Object> afterState,
            Map<String, Object> metadata
    ) {
        UUID id = UUID.randomUUID();
        jdbcAuditLogOperations.insertAuditLog(
                id,
                organizationId,
                actorUserId,
                action,
                entityType,
                entityId,
                beforeState,
                afterState,
                metadata
        );
        return id;
    }
}
