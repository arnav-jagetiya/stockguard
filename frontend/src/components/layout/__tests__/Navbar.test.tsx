import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { Navbar } from '../Navbar';
import { AuthContext, type AuthContextType } from '../../../context/AuthContext';
import { ThemeProvider } from '../../../context/ThemeContext';

const createMockAuth = (overrides?: Partial<AuthContextType>): AuthContextType => ({
  user: null,
  organizations: [],
  currentOrg: null,
  permissions: new Set(),
  role: undefined,
  isAuthenticated: false,
  isLoading: false,
  isDemoVisitor: false,
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
  loginDemoVisitor: vi.fn(),
  switchOrganization: vi.fn(),
  hasPermission: () => false,
  ...overrides,
});

describe('Navbar Component Refinements', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders unauthenticated navigation with Live Demo CTA and Sign In, but no Register buttons', () => {
    const auth = createMockAuth({ isAuthenticated: false });

    render(
      <MemoryRouter>
        <ThemeProvider>
          <AuthContext.Provider value={auth}>
            <Navbar />
          </AuthContext.Provider>
        </ThemeProvider>
      </MemoryRouter>
    );

    // Primary CTA: matches 'Explore Live Demo'
    const demoButtons = screen.getAllByText((_, el) => el?.textContent?.trim() === 'Explore Live Demo');
    expect(demoButtons.length).toBeGreaterThan(0);

    // Sign In CTA
    const signInButton = screen.getByText(/Sign In/i);
    expect(signInButton).toBeDefined();

    // No dead-end Register CTA
    expect(screen.queryByText(/Register/i)).toBeNull();
    expect(screen.queryByText(/Create Account/i)).toBeNull();

    // Verify navigation links
    expect(screen.getByText('Spatial Twin')).toBeDefined();
    expect(screen.getByText('Capabilities')).toBeDefined();
    expect(screen.getByText('Zero-Drift')).toBeDefined();
    expect(screen.getByText('Architecture')).toBeDefined();
  });

  it('toggles mobile drawer navigation when hamburger is clicked', () => {
    const auth = createMockAuth({ isAuthenticated: false });

    render(
      <MemoryRouter>
        <ThemeProvider>
          <AuthContext.Provider value={auth}>
            <Navbar />
          </AuthContext.Provider>
        </ThemeProvider>
      </MemoryRouter>
    );

    const toggleButton = screen.getByLabelText('Open navigation menu');
    expect(toggleButton).toBeDefined();

    // Open drawer
    fireEvent.click(toggleButton);
    expect(screen.getByText('Platform Navigation')).toBeDefined();

    // Close drawer via toggle button
    fireEvent.click(toggleButton);
    expect(screen.queryByText('Platform Navigation')).toBeNull();
  });

  it('renders Operations Console link when user is authenticated', () => {
    const auth = createMockAuth({
      isAuthenticated: true,
      user: {
        id: 'user-1',
        email: 'operator@stockguard.dev',
        fullName: 'Operator User',
        createdAt: new Date().toISOString(),
      },
    });

    render(
      <MemoryRouter>
        <ThemeProvider>
          <AuthContext.Provider value={auth}>
            <Navbar />
          </AuthContext.Provider>
        </ThemeProvider>
      </MemoryRouter>
    );

    const consoleButtons = screen.getAllByText(/Operations Console/i);
    expect(consoleButtons.length).toBeGreaterThan(0);
  });
});
