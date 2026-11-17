import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { DashboardPage } from '../DashboardPage';
import * as dashboardApi from '../../../api/dashboard';
import * as warehouseApi from '../../../api/warehouses';
import { AuthContext, type AuthContextType } from '../../../context/AuthContext';
import type { DashboardSummaryResponse } from '../../../types/dashboard';

vi.mock('../../../api/dashboard');
vi.mock('../../../api/warehouses');

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
  permissions: new Set(['warehouse:manage', 'product:create', 'inventory:reconcile']),
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

describe('DashboardPage Experience & Onboarding', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders structured onboarding journey for empty organizations', async () => {
    const emptySummary: DashboardSummaryResponse = {
      organizationId: 'org-1',
      generatedAt: new Date().toISOString(),
      inventory: {
        totalOnHand: 0,
        totalAvailable: 0,
        totalReserved: 0,
        outOfStockActiveProductsCount: 0,
      },
      catalog: {
        activeProductsCount: 0,
        activeWarehousesCount: 0,
      },
      orders: {
        pendingCount: 0,
        confirmedCount: 0,
        fulfilledCount: 0,
        cancelledCount: 0,
      },
      reservations: {
        activeReservationsCount: 0,
        expiredReservationsCount: 0,
      },
      integrity: {
        openDiscrepanciesCount: 0,
      },
      recentMovements: [],
    };

    vi.mocked(dashboardApi.getDashboardSummaryApi).mockResolvedValue(emptySummary);
    vi.mocked(warehouseApi.listWarehousesApi).mockResolvedValue([]);

    render(
      <MemoryRouter>
        <AuthContext.Provider value={mockAuthContext}>
          <DashboardPage />
        </AuthContext.Provider>
      </MemoryRouter>
    );

    // Verify Onboarding Header and Badge
    await waitFor(() => {
      expect(screen.getByText('INITIAL ONBOARDING')).toBeInTheDocument();
    });

    expect(
      screen.getByText(/Welcome to StockGuard. Let's configure your operational perimeter/i)
    ).toBeInTheDocument();

    // Verify 3 Structured Setup Journey Milestones
    expect(screen.getByText('Provision Storage Facility')).toBeInTheDocument();
    expect(screen.getByText('Define Product Catalog SKUs')).toBeInTheDocument();
    expect(screen.getByText('Record Opening Stock')).toBeInTheDocument();

    // Verify Educational Balance Blueprint
    expect(screen.getByText('How Initial Balances are Established')).toBeInTheDocument();
    expect(screen.getByText(/RECEIPT: \(\+Q on-hand, 0 reserved\)/i)).toBeInTheDocument();

    // Verify Post-Setup Preview
    expect(screen.getByText('What You Will See Once Configured')).toBeInTheDocument();
  });

  it('renders populated command centre with mathematical balance math when data exists', async () => {
    const populatedSummary: DashboardSummaryResponse = {
      organizationId: 'org-1',
      generatedAt: new Date().toISOString(),
      inventory: {
        totalOnHand: 1500,
        totalAvailable: 1200,
        totalReserved: 300,
        outOfStockActiveProductsCount: 0,
      },
      catalog: {
        activeProductsCount: 15,
        activeWarehousesCount: 2,
      },
      orders: {
        pendingCount: 2,
        confirmedCount: 5,
        fulfilledCount: 42,
        cancelledCount: 1,
      },
      reservations: {
        activeReservationsCount: 3,
        expiredReservationsCount: 0,
      },
      integrity: {
        openDiscrepanciesCount: 0,
      },
      recentMovements: [
        {
          transactionId: 'tx-101',
          transactionType: 'RECEIPT',
          referenceType: 'PO',
          referenceId: 'PO-2026-001',
          createdByName: 'Test Admin',
          createdAt: new Date().toISOString(),
        },
      ],
    };

    vi.mocked(dashboardApi.getDashboardSummaryApi).mockResolvedValue(populatedSummary);
    vi.mocked(warehouseApi.listWarehousesApi).mockResolvedValue([
      {
        id: 'wh-1',
        code: 'WH-MAIN',
        name: 'Main Distribution Hub',
        address: '100 Logistics Way',
        isActive: true,
        priority: 1,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
      },
    ]);

    render(
      <MemoryRouter>
        <AuthContext.Provider value={mockAuthContext}>
          <DashboardPage />
        </AuthContext.Provider>
      </MemoryRouter>
    );

    // Verify Real-time snapshot badge
    await waitFor(() => {
      expect(screen.getByText('REAL-TIME SNAPSHOT')).toBeInTheDocument();
    });

    // Invariant Headline
    expect(
      screen.getByText(/Physical On-Hand = Available \+ Active Reservations/i)
    ).toBeInTheDocument();

    // Quantities displayed accurately
    expect(screen.getByText('1,500')).toBeInTheDocument();
    expect(screen.getByText('1,200')).toBeInTheDocument();
    expect(screen.getByText('300')).toBeInTheDocument();

    // Order progression stages
    expect(screen.getByText('STAGE 1 · PENDING')).toBeInTheDocument();
    expect(screen.getByText('STAGE 2 · CONFIRMED')).toBeInTheDocument();
    expect(screen.getByText('STAGE 3 · FULFILLED')).toBeInTheDocument();
  });
});
