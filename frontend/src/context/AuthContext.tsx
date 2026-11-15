import React, { createContext, useContext, useEffect, useState, useCallback } from 'react';
import type { OrganizationSummary, Role, UserSummary } from '../types/auth';
import {
  loginApi,
  logoutApi,
  refreshApi,
  switchOrgApi,
} from '../api/auth';
import {
  setAuthToken,
  setActiveOrganizationId,
  setOnUnauthorized,
} from '../api/client';

interface AuthContextType {
  user: UserSummary | null;
  organizations: OrganizationSummary[];
  currentOrg: OrganizationSummary | null;
  permissions: Set<string>;
  role: Role | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (email: string, pass: string) => Promise<void>;
  logout: () => Promise<void>;
  switchOrganization: (orgId: string) => Promise<void>;
  hasPermission: (permission: string) => boolean;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

const ACTIVE_ORG_STORAGE_KEY = 'stockguard_active_org_id';

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [user, setUser] = useState<UserSummary | null>(null);
  const [organizations, setOrganizations] = useState<OrganizationSummary[]>([]);
  const [currentOrg, setCurrentOrg] = useState<OrganizationSummary | null>(null);
  const [permissions, setPermissions] = useState<Set<string>>(new Set());
  const [isLoading, setIsLoading] = useState(true);

  const applyOrganization = useCallback(async (org: OrganizationSummary) => {
    setCurrentOrg(org);
    setActiveOrganizationId(org.id);
    localStorage.setItem(ACTIVE_ORG_STORAGE_KEY, org.id);

    try {
      const switchRes = await switchOrgApi(org.id);
      setPermissions(new Set(switchRes.permissions));
    } catch {
      // Fallback permissions based on role if switch-org call encounters transient issue
      setPermissions(getDefaultPermissionsForRole(org.role));
    }
  }, []);

  const handleSilentRefresh = useCallback(async (): Promise<boolean> => {
    try {
      const res = await refreshApi();
      setAuthToken(res.accessToken);
      setUser(res.user);
      setOrganizations(res.organizations);

      const savedOrgId = localStorage.getItem(ACTIVE_ORG_STORAGE_KEY);
      const targetOrg = res.organizations.find((o) => o.id === savedOrgId) || res.organizations[0];

      if (targetOrg) {
        await applyOrganization(targetOrg);
      }
      return true;
    } catch {
      setAuthToken(null);
      setUser(null);
      setOrganizations([]);
      setCurrentOrg(null);
      setPermissions(new Set());
      setActiveOrganizationId(null);
      return false;
    }
  }, [applyOrganization]);

  useEffect(() => {
    // Register global 401 recovery handler in API client
    setOnUnauthorized(handleSilentRefresh);

    // Initial session restoration attempt from cookie
    const initSession = async () => {
      try {
        await handleSilentRefresh();
      } finally {
        setIsLoading(false);
      }
    };
    initSession();
  }, [handleSilentRefresh]);

  const login = async (email: string, pass: string) => {
    setIsLoading(true);
    try {
      const res = await loginApi(email, pass);
      setAuthToken(res.accessToken);
      setUser(res.user);
      setOrganizations(res.organizations);

      const savedOrgId = localStorage.getItem(ACTIVE_ORG_STORAGE_KEY);
      const targetOrg = res.organizations.find((o) => o.id === savedOrgId) || res.organizations[0];

      if (targetOrg) {
        await applyOrganization(targetOrg);
      }
    } finally {
      setIsLoading(false);
    }
  };

  const logout = async () => {
    try {
      await logoutApi();
    } catch {
      // Best-effort logout
    } finally {
      setAuthToken(null);
      setUser(null);
      setOrganizations([]);
      setCurrentOrg(null);
      setPermissions(new Set());
      setActiveOrganizationId(null);
      localStorage.removeItem(ACTIVE_ORG_STORAGE_KEY);
    }
  };

  const switchOrganization = async (orgId: string) => {
    const org = organizations.find((o) => o.id === orgId);
    if (!org) return;
    await applyOrganization(org);
  };

  const hasPermission = (permission: string): boolean => {
    if (!currentOrg) return false;
    if (currentOrg.role === 'ADMIN') return true;
    return permissions.has(permission);
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        organizations,
        currentOrg,
        permissions,
        role: currentOrg ? currentOrg.role : null,
        isAuthenticated: !!user && !!currentOrg,
        isLoading,
        login,
        logout,
        switchOrganization,
        hasPermission,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};

function getDefaultPermissionsForRole(role: Role): Set<string> {
  const all = [
    'dashboard:view',
    'reports:view',
    'inventory:view',
    'inventory:receive',
    'inventory:adjust',
    'inventory:reconcile',
    'product:view',
    'product:manage',
    'warehouse:view',
    'warehouse:manage',
    'transfer:view',
    'transfer:execute',
    'order:view',
    'order:create',
    'order:confirm',
    'order:fulfill',
    'order:cancel',
    'reservation:view',
    'reservation:create',
    'reservation:release',
    'membership:view',
    'membership:manage',
    'audit:view',
    'organization:manage',
  ];

  if (role === 'ADMIN') return new Set(all);
  if (role === 'MANAGER') {
    return new Set(all.filter((p) => p !== 'organization:manage' && p !== 'membership:manage'));
  }
  if (role === 'OPERATOR') {
    return new Set([
      'dashboard:view',
      'reports:view',
      'inventory:view',
      'inventory:receive',
      'inventory:adjust',
      'product:view',
      'warehouse:view',
      'transfer:view',
      'transfer:execute',
      'order:view',
      'order:create',
      'order:confirm',
      'order:fulfill',
      'order:cancel',
      'reservation:view',
      'reservation:create',
      'reservation:release',
      'membership:view',
    ]);
  }
  return new Set([
    'dashboard:view',
    'reports:view',
    'inventory:view',
    'product:view',
    'warehouse:view',
    'transfer:view',
    'order:view',
    'reservation:view',
    'membership:view',
  ]);
}
