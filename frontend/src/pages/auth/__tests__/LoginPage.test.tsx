import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { LoginPage } from '../LoginPage';
import { AuthContext, type AuthContextType } from '../../../context/AuthContext';
import { ThemeProvider } from '../../../context/ThemeContext';

const mockNavigate = vi.fn();

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual('react-router-dom');
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  };
});

const createMockAuth = (overrides?: Partial<AuthContextType>): AuthContextType => ({
  user: null,
  organizations: [],
  currentOrg: null,
  permissions: new Set(),
  role: null,
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

describe('LoginPage Public Demo Experience', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders public live demonstration card with Explore as Demo Visitor button', () => {
    const auth = createMockAuth();
    render(
      <MemoryRouter>
        <ThemeProvider>
          <AuthContext.Provider value={auth}>
            <LoginPage />
          </AuthContext.Provider>
        </ThemeProvider>
      </MemoryRouter>
    );

    expect(screen.getByText('Public Demonstration')).toBeInTheDocument();
    expect(screen.getByText('READ-ONLY')).toBeInTheDocument();
    expect(screen.getByText('Explore as Demo Visitor')).toBeInTheDocument();
  });

  it('invokes loginDemoVisitor and navigates to /app/dashboard when button is clicked', async () => {
    const loginDemoVisitor = vi.fn().mockResolvedValue(undefined);
    const auth = createMockAuth({ loginDemoVisitor });

    render(
      <MemoryRouter>
        <ThemeProvider>
          <AuthContext.Provider value={auth}>
            <LoginPage />
          </AuthContext.Provider>
        </ThemeProvider>
      </MemoryRouter>
    );

    const demoBtn = screen.getByRole('button', { name: /explore as demo visitor/i });
    fireEvent.click(demoBtn);

    await waitFor(() => {
      expect(loginDemoVisitor).toHaveBeenCalledTimes(1);
      expect(mockNavigate).toHaveBeenCalledWith('/app/dashboard');
    });
  });

  it('automatically initiates demo login when URL contains ?demo=true', async () => {
    const loginDemoVisitor = vi.fn().mockResolvedValue(undefined);
    const auth = createMockAuth({ loginDemoVisitor });

    render(
      <MemoryRouter initialEntries={['/login?demo=true']}>
        <ThemeProvider>
          <AuthContext.Provider value={auth}>
            <LoginPage />
          </AuthContext.Provider>
        </ThemeProvider>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(loginDemoVisitor).toHaveBeenCalledTimes(1);
      const passedSignal = loginDemoVisitor.mock.calls[0][0];
      expect(passedSignal).toBeInstanceOf(AbortSignal);
      expect(mockNavigate).toHaveBeenCalledWith('/app/dashboard');
    });
  });

  it('aborts demo auto-login and does not navigate when unmounted during in-flight request', async () => {
    let capturedSignal: AbortSignal | undefined;
    let resolveLogin: () => void = () => {};
    const loginDemoVisitor = vi.fn((signal?: AbortSignal) => {
      capturedSignal = signal;
      return new Promise<void>((resolve) => {
        resolveLogin = resolve;
      });
    });
    const auth = createMockAuth({ loginDemoVisitor });

    const { unmount } = render(
      <MemoryRouter initialEntries={['/login?demo=true']}>
        <ThemeProvider>
          <AuthContext.Provider value={auth}>
            <LoginPage />
          </AuthContext.Provider>
        </ThemeProvider>
      </MemoryRouter>
    );

    expect(loginDemoVisitor).toHaveBeenCalledTimes(1);
    expect(capturedSignal).toBeDefined();
    expect(capturedSignal?.aborted).toBe(false);

    unmount();

    expect(capturedSignal?.aborted).toBe(true);

    resolveLogin();
    await new Promise((r) => setTimeout(r, 20));

    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('displays user-friendly error message when demo login fails', async () => {
    const loginDemoVisitor = vi.fn().mockRejectedValue(new Error('Public demo is disabled in this environment.'));
    const auth = createMockAuth({ loginDemoVisitor });

    render(
      <MemoryRouter>
        <ThemeProvider>
          <AuthContext.Provider value={auth}>
            <LoginPage />
          </AuthContext.Provider>
        </ThemeProvider>
      </MemoryRouter>
    );

    const demoBtn = screen.getByRole('button', { name: /explore as demo visitor/i });
    fireEvent.click(demoBtn);

    await waitFor(() => {
      expect(screen.getByTestId('login-error')).toHaveTextContent('Public demo is disabled in this environment.');
    });
  });
});
