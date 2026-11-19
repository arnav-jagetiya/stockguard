import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { TeamPage } from '../TeamPage';
import * as orgsApi from '../../../api/organizations';
import { AuthContext, type AuthContextType } from '../../../context/AuthContext';
import { ToastProvider } from '../../../context/ToastContext';
import type { OrganizationMember } from '../../../types/organization';

vi.mock('../../../api/organizations');

const mockAuthContext: AuthContextType = {
  user: {
    id: 'user-admin',
    email: 'admin@stockguard.dev',
    fullName: 'Test Admin',
    createdAt: new Date().toISOString(),
  },
  organizations: [
    {
      id: 'org-1',
      name: 'Acme Logistics',
      role: 'ADMIN',
    },
  ],
  currentOrg: {
    id: 'org-1',
    name: 'Acme Logistics',
    role: 'ADMIN',
  },
  permissions: new Set(['membership:view', 'membership:manage']),
  role: 'ADMIN',
  isAuthenticated: true,
  isLoading: false,
  isDemoVisitor: false,
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
  loginDemoVisitor: vi.fn(),
  switchOrganization: vi.fn(),
  hasPermission: () => true,
};

const mockMembers: OrganizationMember[] = [
  {
    id: 'mem-1',
    organizationId: 'org-1',
    userId: 'user-admin',
    role: 'ADMIN',
    active: true,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
  },
  {
    id: 'mem-2',
    organizationId: 'org-1',
    userId: 'user-op-1',
    role: 'OPERATOR',
    active: true,
    createdAt: '2026-01-02T00:00:00Z',
    updatedAt: '2026-01-02T00:00:00Z',
  },
  {
    id: 'mem-3',
    organizationId: 'org-1',
    userId: 'user-viewer-1',
    role: 'VIEWER',
    active: false,
    createdAt: '2026-01-03T00:00:00Z',
    updatedAt: '2026-01-03T00:00:00Z',
  },
];

describe('TeamPage Access Governance & Filtering', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(orgsApi.getMembershipsApi).mockResolvedValue(mockMembers);
  });

  it('renders members list and shows total count', async () => {
    render(
      <AuthContext.Provider value={mockAuthContext}>
        <ToastProvider>
          <TeamPage />
        </ToastProvider>
      </AuthContext.Provider>
    );

    await waitFor(() => {
      expect(screen.getByText('user-admin')).toBeInTheDocument();
      expect(screen.getByText('user-op-1')).toBeInTheDocument();
      expect(screen.getByText('user-viewer-1')).toBeInTheDocument();
    });

    expect(screen.getByText(/Showing 3 of 3 member\(s\)/i)).toBeInTheDocument();
  });

  it('filters members by user ID search query', async () => {
    render(
      <AuthContext.Provider value={mockAuthContext}>
        <ToastProvider>
          <TeamPage />
        </ToastProvider>
      </AuthContext.Provider>
    );

    await waitFor(() => {
      expect(screen.getByText('user-admin')).toBeInTheDocument();
    });

    const searchInput = screen.getByLabelText(/Search members by user ID/i);
    fireEvent.change(searchInput, { target: { value: 'op-1' } });

    expect(screen.getByText('user-op-1')).toBeInTheDocument();
    expect(screen.queryByText('user-admin')).not.toBeInTheDocument();
    expect(screen.queryByText('user-viewer-1')).not.toBeInTheDocument();
    expect(screen.getByText(/Showing 1 of 3 member\(s\)/i)).toBeInTheDocument();
  });

  it('filters members by role dropdown', async () => {
    render(
      <AuthContext.Provider value={mockAuthContext}>
        <ToastProvider>
          <TeamPage />
        </ToastProvider>
      </AuthContext.Provider>
    );

    await waitFor(() => {
      expect(screen.getByText('user-admin')).toBeInTheDocument();
    });

    const roleSelect = screen.getByLabelText(/Filter by role/i);
    fireEvent.change(roleSelect, { target: { value: 'OPERATOR' } });

    expect(screen.getByText('user-op-1')).toBeInTheDocument();
    expect(screen.queryByText('user-admin')).not.toBeInTheDocument();
    expect(screen.queryByText('user-viewer-1')).not.toBeInTheDocument();
  });
});
