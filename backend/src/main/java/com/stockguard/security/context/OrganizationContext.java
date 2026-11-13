package com.stockguard.security.context;

import com.stockguard.organization.domain.Permission;
import com.stockguard.organization.domain.Role;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable security and tenant context active on the current execution thread.
 */
public record OrganizationContext(
        UUID organizationId,
        UUID userId,
        Role role,
        Set<String> permissions
) {
    public OrganizationContext {
        Objects.requireNonNull(organizationId, "Organization ID must not be null");
        Objects.requireNonNull(userId, "User ID must not be null");
        Objects.requireNonNull(role, "Role must not be null");
        permissions = permissions != null ? Collections.unmodifiableSet(permissions) : Collections.emptySet();
    }

    public boolean hasPermission(Permission permission) {
        return permission != null && permissions.contains(permission.getAuthority());
    }

    public boolean hasPermission(String authority) {
        return authority != null && permissions.contains(authority);
    }
}
