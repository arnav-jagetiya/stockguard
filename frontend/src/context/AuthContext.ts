import { createContext, useContext } from 'react';
import type { OrganizationSummary, RegisterRequest, Role, UserSummary } from '../types/auth';

export interface AuthContextType {
  user: UserSummary | null;
  organizations: OrganizationSummary[];
  currentOrg: OrganizationSummary | null;
  permissions: Set<string>;
  role: Role | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  isDemoVisitor: boolean;
  login: (email: string, pass: string) => Promise<void>;
  register: (data: RegisterRequest) => Promise<void>;
  logout: () => Promise<void>;
  loginDemoVisitor: (signal?: AbortSignal) => Promise<void>;
  switchOrganization: (orgId: string) => Promise<void>;
  hasPermission: (permission: string) => boolean;
}

export const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const useAuth = (): AuthContextType => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};

export { AuthProvider } from './AuthProvider';
