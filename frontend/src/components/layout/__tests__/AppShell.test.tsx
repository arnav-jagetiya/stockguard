import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { AppShell } from '../AppShell';
import { AuthContext, type AuthContextType } from '../../../context/AuthContext';
import { ThemeProvider } from '../../../context/ThemeContext';

const createMockAuth = (overrides?: Partial<AuthContextType>): AuthContextType => ({
  user: {
    id: 'user-1',
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
  permissions: new Set(['dashboard:view']),
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
  ...overrides,
});

describe('AppShell Layout & Demo Banner', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders persistent demo banner when isDemoVisitor is true', () => {
    const auth = createMockAuth({
      isDemoVisitor: true,
      user: {
        id: 'demo-user-id',
        email: 'demo.visitor@stockguard.dev',
        fullName: 'Demo Visitor',
        createdAt: new Date().toISOString(),
      },
    });

    render(
      <MemoryRouter>
        <ThemeProvider>
          <AuthContext.Provider value={auth}>
            <AppShell />
          </AuthContext.Provider>
        </ThemeProvider>
      </MemoryRouter>
    );

    const banner = screen.getByTestId('demo-visitor-banner');
    expect(banner).toBeInTheDocument();
    expect(banner).toHaveTextContent('Public Demo Mode (Read-Only)');
    expect(banner).toHaveTextContent('VIEWER ROLE');
  });

  it('does not render demo banner for normal authenticated users', () => {
    const auth = createMockAuth({
      isDemoVisitor: false,
      user: {
        id: 'user-id',
        email: 'admin@stockguard.dev',
        fullName: 'Admin User',
        createdAt: new Date().toISOString(),
      },
    });

    render(
      <MemoryRouter>
        <ThemeProvider>
          <AuthContext.Provider value={auth}>
            <AppShell />
          </AuthContext.Provider>
        </ThemeProvider>
      </MemoryRouter>
    );

    expect(screen.queryByTestId('demo-visitor-banner')).not.toBeInTheDocument();
  });
});
