import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { ProductsPage } from '../ProductsPage';
import * as productsApi from '../../../api/products';
import { AuthContext, type AuthContextType } from '../../../context/AuthContext';
import { ToastProvider } from '../../../context/ToastContext';
import type { Product } from '../../../types/product';

vi.mock('../../../api/products');

const mockAuthContext: AuthContextType = {
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
  permissions: new Set(['product:view', 'product:manage']),
  role: 'ADMIN',
  isAuthenticated: true,
  isLoading: false,
  isDemoVisitor: false,
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
  loginDemoVisitor: vi.fn(),
  switchOrganization: vi.fn(),
  hasPermission: (perm: string) => perm === 'product:manage' || perm === 'product:view',
};

const mockProducts: Product[] = [
  {
    id: 'prod-1',
    organizationId: 'org-1',
    sku: 'SKU-WIDGET-A',
    name: 'Industrial Widget Alpha',
    description: 'High durability alpha widget',
    active: true,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  },
  {
    id: 'prod-2',
    organizationId: 'org-1',
    sku: 'SKU-WIDGET-B',
    name: 'Industrial Widget Beta',
    description: 'Precision beta widget',
    active: true,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  },
];

function renderProductsPage() {
  return render(
    <AuthContext.Provider value={mockAuthContext}>
      <ToastProvider>
        <ProductsPage />
      </ToastProvider>
    </AuthContext.Provider>
  );
}

describe('ProductsPage Lifecycle & Search Concurrency', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(productsApi.listProductsApi).mockResolvedValue(mockProducts);
  });

  it('renders product catalog items on initial load', async () => {
    renderProductsPage();

    await waitFor(() => {
      expect(screen.getByText('Product Catalog')).toBeInTheDocument();
      expect(screen.getByText('SKU-WIDGET-A')).toBeInTheDocument();
      expect(screen.getByText('SKU-WIDGET-B')).toBeInTheDocument();
    });
  });

  it('does not prematurely set loading indicator on keystroke before 300ms debounce', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderProductsPage();

    await waitFor(() => {
      expect(screen.getByText('SKU-WIDGET-A')).toBeInTheDocument();
    });

    const searchInput = screen.getByPlaceholderText('Filter by SKU or name...');
    fireEvent.change(searchInput, { target: { value: 'Alpha' } });

    // Table should still be rendered (not immediately replaced by TableSkeleton)
    expect(screen.getByText('SKU-WIDGET-A')).toBeInTheDocument();

    // Advance timers past debounce threshold (300ms)
    act(() => {
      vi.advanceTimersByTime(350);
    });

    await waitFor(() => {
      expect(productsApi.listProductsApi).toHaveBeenCalledWith('Alpha', false, expect.any(AbortSignal));
    });

    vi.useRealTimers();
  });

  it('aborts previous in-flight request when filters change rapidly', async () => {
    const signals: AbortSignal[] = [];
    vi.mocked(productsApi.listProductsApi).mockImplementation((_search, _active, signal) => {
      if (signal) signals.push(signal);
      return new Promise<Product[]>((resolve) => {
        setTimeout(() => resolve(mockProducts), 100);
      });
    });

    vi.useFakeTimers({ shouldAdvanceTime: true });
    renderProductsPage();

    act(() => {
      vi.advanceTimersByTime(10);
    });

    const searchInput = screen.getByPlaceholderText('Filter by SKU or name...');

    // First rapid keystroke
    fireEvent.change(searchInput, { target: { value: 'First' } });
    act(() => {
      vi.advanceTimersByTime(350);
    });

    // Second rapid keystroke before first request finishes
    fireEvent.change(searchInput, { target: { value: 'Second' } });
    act(() => {
      vi.advanceTimersByTime(350);
    });

    // The first request's signal should have been aborted when the second debounced effect ran
    expect(signals.length).toBeGreaterThanOrEqual(2);
    expect(signals[0].aborted).toBe(true);

    vi.useRealTimers();
  });

  it('displays error and clears it when retry is clicked', async () => {
    vi.mocked(productsApi.listProductsApi).mockRejectedValueOnce(new Error('Network error loading SKUs'));

    renderProductsPage();

    await waitFor(() => {
      expect(screen.getByText('Network error loading SKUs')).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
    });

    // Now resolve successfully on retry
    vi.mocked(productsApi.listProductsApi).mockResolvedValueOnce(mockProducts);

    const retryBtn = screen.getByRole('button', { name: /retry/i });
    fireEvent.click(retryBtn);

    await waitFor(() => {
      expect(screen.queryByText('Network error loading SKUs')).not.toBeInTheDocument();
      expect(screen.getByText('SKU-WIDGET-A')).toBeInTheDocument();
    });
  });

  it('aborts in-flight request on unmount without throwing unhandled rejection', async () => {
    let capturedSignal: AbortSignal | undefined;
    vi.mocked(productsApi.listProductsApi).mockImplementation((_s, _a, signal) => {
      capturedSignal = signal;
      return new Promise<Product[]>(() => {
        // Hang unresolved
      });
    });

    const { unmount } = renderProductsPage();

    await waitFor(() => {
      expect(capturedSignal).toBeDefined();
    });

    expect(capturedSignal?.aborted).toBe(false);

    unmount();

    expect(capturedSignal?.aborted).toBe(true);
  });
});
