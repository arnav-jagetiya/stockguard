import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { OrdersPage } from '../OrdersPage';
import * as ordersApi from '../../../api/orders';
import * as productsApi from '../../../api/products';
import { AuthContext, type AuthContextType } from '../../../context/AuthContext';
import { ToastProvider } from '../../../context/ToastContext';
import type { Order } from '../../../types/order';

vi.mock('../../../api/orders');
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
  permissions: new Set(['order:view', 'order:create', 'order:confirm', 'order:fulfill', 'order:cancel']),
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

describe('OrdersPage Domain Lifecycle & State Mapping', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders order list with authoritative lifecycle badges including EXPIRED', async () => {
    const mockOrders: Order[] = [
      {
        id: 'ord-1',
        organizationId: 'org-1',
        orderNumber: 'ORD-2026-001',
        status: 'PENDING',
        currency: 'USD',
        totalAmount: 150.0,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
      {
        id: 'ord-2',
        organizationId: 'org-1',
        orderNumber: 'ORD-2026-002',
        status: 'CONFIRMED',
        currency: 'USD',
        totalAmount: 250.0,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
      {
        id: 'ord-3',
        organizationId: 'org-1',
        orderNumber: 'ORD-2026-003',
        status: 'FULFILLED',
        currency: 'USD',
        totalAmount: 350.0,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
      {
        id: 'ord-4',
        organizationId: 'org-1',
        orderNumber: 'ORD-2026-004',
        status: 'EXPIRED',
        currency: 'USD',
        totalAmount: 450.0,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
    ];

    vi.mocked(ordersApi.listOrdersApi).mockResolvedValue(mockOrders);
    vi.mocked(productsApi.listProductsApi).mockResolvedValue([]);

    render(
      <MemoryRouter>
        <AuthContext.Provider value={mockAuthContext}>
          <ToastProvider>
            <OrdersPage />
          </ToastProvider>
        </AuthContext.Provider>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('ORD-2026-001')).toBeInTheDocument();
      expect(screen.getByText('ORD-2026-002')).toBeInTheDocument();
      expect(screen.getByText('ORD-2026-003')).toBeInTheDocument();
      expect(screen.getByText('ORD-2026-004')).toBeInTheDocument();
    });

    expect(screen.getByText('PENDING')).toBeInTheDocument();
    expect(screen.getByText('CONFIRMED')).toBeInTheDocument();
    expect(screen.getByText('FULFILLED')).toBeInTheDocument();
    expect(screen.getByText('EXPIRED')).toBeInTheDocument();

    // Verify filter options include Expired
    const filterSelect = screen.getByLabelText(/filter by status/i);
    expect(filterSelect).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Expired' })).toBeInTheDocument();
  });
});
