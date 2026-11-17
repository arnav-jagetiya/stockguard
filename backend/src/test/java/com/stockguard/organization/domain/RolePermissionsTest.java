package com.stockguard.organization.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RolePermissionsTest {

    @Test
    @DisplayName("VIEWER role has read permissions for all 10 operational modules")
    void viewerRoleHasTenModuleReadPermissions() {
        Set<String> authorities = RolePermissions.getPermissionAuthoritiesForRole(Role.VIEWER);

        // 1. Dashboard
        assertThat(authorities).contains("dashboard:view");
        // 2. Catalog (Products)
        assertThat(authorities).contains("product:view");
        // 3. Warehouses
        assertThat(authorities).contains("warehouse:view");
        // 4. Inventory & Ledger
        assertThat(authorities).contains("inventory:view");
        // 5. Orders
        assertThat(authorities).contains("order:view");
        // 6. Reservations
        assertThat(authorities).contains("reservation:view");
        // 7. Transfers
        assertThat(authorities).contains("transfer:view");
        // 8. Reconciliation
        assertThat(authorities).contains("inventory:discrepancy:view");
        // 9. Audit Trail
        assertThat(authorities).contains("audit:view");
        // 10. Access & Team
        assertThat(authorities).contains("membership:view");
    }

    @Test
    @DisplayName("VIEWER role does not have mutation permissions")
    void viewerRoleDoesNotHaveMutationPermissions() {
        Set<String> authorities = RolePermissions.getPermissionAuthoritiesForRole(Role.VIEWER);

        assertThat(authorities).doesNotContain("inventory:reconcile");
        assertThat(authorities).doesNotContain("inventory:receive");
        assertThat(authorities).doesNotContain("inventory:adjust");
        assertThat(authorities).doesNotContain("product:manage");
        assertThat(authorities).doesNotContain("warehouse:manage");
        assertThat(authorities).doesNotContain("order:create");
        assertThat(authorities).doesNotContain("order:fulfill");
        assertThat(authorities).doesNotContain("reservation:create");
        assertThat(authorities).doesNotContain("transfer:execute");
        assertThat(authorities).doesNotContain("membership:manage");
    }

    @Test
    @DisplayName("MANAGER and ADMIN roles include discrepancy view permissions")
    void managerAndAdminIncludeDiscrepancyView() {
        Set<String> managerAuthorities = RolePermissions.getPermissionAuthoritiesForRole(Role.MANAGER);
        assertThat(managerAuthorities).contains("inventory:discrepancy:view");
        assertThat(managerAuthorities).contains("inventory:reconcile");

        Set<String> adminAuthorities = RolePermissions.getPermissionAuthoritiesForRole(Role.ADMIN);
        assertThat(adminAuthorities).contains("inventory:discrepancy:view");
        assertThat(adminAuthorities).contains("inventory:reconcile");
    }
}
