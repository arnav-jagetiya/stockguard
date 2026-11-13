package com.stockguard.security.service;

import com.stockguard.organization.domain.Role;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * Production implementation of {@link TenantMembershipValidator} querying
 * {@code organization_memberships} in PostgreSQL.
 */
@Service
@Profile("!test")
public class JdbcTenantMembershipValidator implements TenantMembershipValidator {

    private static final Logger log = LoggerFactory.getLogger(JdbcTenantMembershipValidator.class);

    private static final String SQL_VALIDATE_MEMBERSHIP = """
            SELECT role, active
            FROM organization_memberships
            WHERE user_id = ?
              AND organization_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcTenantMembershipValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<TenantMembershipDetails> validateMembership(UUID userId, UUID organizationId) {
        if (userId == null || organizationId == null) {
            return Optional.empty();
        }

        try {
            return jdbcTemplate.query(
                    SQL_VALIDATE_MEMBERSHIP,
                    rs -> {
                        if (rs.next()) {
                            String roleStr = rs.getString("role");
                            boolean active = rs.getBoolean("active");
                            Role role = Role.valueOf(roleStr);
                            return Optional.of(new TenantMembershipDetails(organizationId, userId, role, active));
                        }
                        return Optional.empty();
                    },
                    userId,
                    organizationId
            );
        } catch (DataAccessException ex) {
            log.error("Database error during tenant membership validation for user {} and org {}: {}",
                    userId, organizationId, ex.getMessage(), ex);
            throw ex;
        }
    }
}
