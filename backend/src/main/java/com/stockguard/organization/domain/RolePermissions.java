package com.stockguard.organization.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Authoritative mapping of organization membership roles to granular permissions
 * per the approved role-permission matrix.
 */
public final class RolePermissions {

    private static final Set<Permission> ADMIN_PERMISSIONS = Collections.unmodifiableSet(
            EnumSet.allOf(Permission.class)
    );

    private static final Set<Permission> MANAGER_PERMISSIONS = Collections.unmodifiableSet(
            EnumSet.of(
                    Permission.DASHBOARD_VIEW,
                    Permission.REPORTS_VIEW,
                    Permission.INVENTORY_VIEW,
                    Permission.INVENTORY_RECEIVE,
                    Permission.INVENTORY_ADJUST,
                    Permission.INVENTORY_RECONCILE,
                    Permission.PRODUCT_VIEW,
                    Permission.PRODUCT_MANAGE,
                    Permission.WAREHOUSE_VIEW,
                    Permission.WAREHOUSE_MANAGE,
                    Permission.TRANSFER_VIEW,
                    Permission.TRANSFER_EXECUTE,
                    Permission.ORDER_VIEW,
                    Permission.ORDER_CREATE,
                    Permission.ORDER_CONFIRM,
                    Permission.ORDER_FULFILL,
                    Permission.ORDER_CANCEL,
                    Permission.RESERVATION_VIEW,
                    Permission.RESERVATION_CREATE,
                    Permission.RESERVATION_RELEASE,
                    Permission.MEMBERSHIP_VIEW,
                    Permission.AUDIT_VIEW
            )
    );

    private static final Set<Permission> OPERATOR_PERMISSIONS = Collections.unmodifiableSet(
            EnumSet.of(
                    Permission.DASHBOARD_VIEW,
                    Permission.REPORTS_VIEW,
                    Permission.INVENTORY_VIEW,
                    Permission.INVENTORY_RECEIVE,
                    Permission.INVENTORY_ADJUST,
                    Permission.PRODUCT_VIEW,
                    Permission.WAREHOUSE_VIEW,
                    Permission.TRANSFER_VIEW,
                    Permission.TRANSFER_EXECUTE,
                    Permission.ORDER_VIEW,
                    Permission.ORDER_CREATE,
                    Permission.ORDER_CONFIRM,
                    Permission.ORDER_FULFILL,
                    Permission.ORDER_CANCEL,
                    Permission.RESERVATION_VIEW,
                    Permission.RESERVATION_CREATE,
                    Permission.RESERVATION_RELEASE,
                    Permission.MEMBERSHIP_VIEW
            )
    );

    private static final Set<Permission> VIEWER_PERMISSIONS = Collections.unmodifiableSet(
            EnumSet.of(
                    Permission.DASHBOARD_VIEW,
                    Permission.REPORTS_VIEW,
                    Permission.INVENTORY_VIEW,
                    Permission.PRODUCT_VIEW,
                    Permission.WAREHOUSE_VIEW,
                    Permission.TRANSFER_VIEW,
                    Permission.ORDER_VIEW,
                    Permission.RESERVATION_VIEW,
                    Permission.MEMBERSHIP_VIEW
            )
    );

    private static final Map<Role, Set<Permission>> ROLE_TO_PERMISSIONS = Map.of(
            Role.ADMIN, ADMIN_PERMISSIONS,
            Role.MANAGER, MANAGER_PERMISSIONS,
            Role.OPERATOR, OPERATOR_PERMISSIONS,
            Role.VIEWER, VIEWER_PERMISSIONS
    );

    private RolePermissions() {
    }

    public static Set<Permission> getPermissionsForRole(Role role) {
        if (role == null) {
            return Collections.emptySet();
        }
        return ROLE_TO_PERMISSIONS.getOrDefault(role, Collections.emptySet());
    }

    public static Set<String> getPermissionAuthoritiesForRole(Role role) {
        return getPermissionsForRole(role).stream()
                .map(Permission::getAuthority)
                .collect(Collectors.toUnmodifiableSet());
    }
}
