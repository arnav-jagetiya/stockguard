import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { TransfersPage } from '../TransfersPage';
import * as transfersApi from '../../../api/transfers';
import * as warehousesApi from '../../../api/warehouses';
import * as productsApi from '../../../api/products';
import { AuthContext, type AuthContextType } from '../../../context/AuthContext';
import { ToastProvider } from '../../../context/ToastContext';
import type { Transfer } from '../../../types/transfer';
import type { Warehouse } from '../../../types/warehouse';
import type { Product } from '../../../types/product';

vi.mock('../../../api/transfers');
vi.mock('../../../api/warehouses');
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
  permissions: new Set(['transfer:view', 'transfer:execute']),
  role: 'ADMIN',
  isAuthenticated: true,
  isLoading: false,
  isDemoVisitor: false,
  login: vi.fn(),
  register: vi.fn(),
  logout: vi.fn(),
  loginDemoVisitor: vi.fn(),
  switchOrganization: vi.fn(),
  hasPermission: (perm: string) => perm === 'transfer:execute' || perm === 'transfer:view',
};

const mockWarehouses: Warehouse[] = [
  {
    id: 'wh-1',
    organizationId: 'org-1',
    code: 'WH-NORTH',
    name: 'North Fulfillment Center',
    address: '100 North Way',
    active: true,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  },
  {
    id: 'wh-2',
    organizationId: 'org-1',
    code: 'WH-SOUTH',
    name: 'South Distribution Hub',
    address: '200 South Ave',
    active: true,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  },
];

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

const mockTransfers: Transfer[] = [
  {
    id: 'tx-1',
    organizationId: 'org-1',
    sourceWarehouseId: 'wh-1',
    destinationWarehouseId: 'wh-2',
    notes: 'Restock seasonal inventory',
    items: [{ productId: 'prod-1', quantity: 15 }],
    actorUserId: 'user-1',
    completedAt: new Date().toISOString(),
    createdAt: new Date().toISOString(),
  },
];

function renderTransfersPage() {
  return render(
    <AuthContext.Provider value={mockAuthContext}>
      <ToastProvider>
        <TransfersPage />
      </ToastProvider>
    </AuthContext.Provider>
  );
}

describe('TransfersPage Modal & Duplicate Item Prevention', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(transfersApi.listTransfersApi).mockResolvedValue(mockTransfers);
    vi.mocked(warehousesApi.listWarehousesApi).mockResolvedValue(mockWarehouses);
    vi.mocked(productsApi.listProductsApi).mockResolvedValue(mockProducts);
    vi.mocked(transfersApi.executeTransferApi).mockResolvedValue(mockTransfers[0]);
  });

  it('renders existing transfers list with warehouse facility filter', async () => {
    renderTransfersPage();

    await waitFor(() => {
      expect(screen.getByText('Facility Transfers')).toBeInTheDocument();
      expect(screen.getByText('WH-NORTH')).toBeInTheDocument();
      expect(screen.getByText('WH-SOUTH')).toBeInTheDocument();
      expect(screen.getByText('Restock seasonal inventory')).toBeInTheDocument();
    });

    expect(screen.getByText(/Showing 1 transfer\(s\)/i)).toBeInTheDocument();
  });

  it('prevents selecting a product already included in another transfer line', async () => {
    renderTransfersPage();

    await waitFor(() => {
      expect(screen.getByText('Execute Transfer')).toBeInTheDocument();
    });

    // Open transfer modal
    fireEvent.click(screen.getByText('Execute Transfer'));

    expect(screen.getByText('Execute Inter-Facility Stock Transfer')).toBeInTheDocument();

    // Initial state: line 1 defaults to prod-1
    const line1Select = screen.getByLabelText('Product for line 1') as HTMLSelectElement;
    expect(line1Select.value).toBe('prod-1');

    // Click "Add Product"
    const addProductBtn = screen.getByRole('button', { name: /Add Product/i });
    fireEvent.click(addProductBtn);

    // Line 2 is added and automatically picks the next available product (prod-2)
    const line2Select = screen.getByLabelText('Product for line 2') as HTMLSelectElement;
    expect(line2Select.value).toBe('prod-2');

    // Check options on line 2: prod-1 should NOT be an option (filtered out to prevent duplicate)
    const line2Options = Array.from(line2Select.options).map((opt) => opt.value);
    expect(line2Options).toContain('prod-2');
    expect(line2Options).not.toContain('prod-1');

    // When all products (prod-1 and prod-2) are assigned, "Add Product" button is disabled
    expect(addProductBtn).toBeDisabled();
    expect(screen.getByText('All products added')).toBeInTheDocument();
  });

  it('successfully submits valid multi-product transfer request', async () => {
    renderTransfersPage();

    await waitFor(() => {
      expect(screen.getByText('Execute Transfer')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Execute Transfer'));

    // Set quantity for line 1
    const qty1 = screen.getByLabelText('Quantity for line 1');
    fireEvent.change(qty1, { target: { value: '10' } });

    // Add line 2
    fireEvent.click(screen.getByRole('button', { name: /Add Product/i }));
    const qty2 = screen.getByLabelText('Quantity for line 2');
    fireEvent.change(qty2, { target: { value: '25' } });

    // Fill notes
    fireEvent.change(screen.getByLabelText(/Transfer Notes \/ Authorization/i), {
      target: { value: 'Valid multi-line transfer test' },
    });

    // Submit transfer
    fireEvent.click(screen.getByRole('button', { name: 'Execute Relocation' }));

    await waitFor(() => {
      expect(transfersApi.executeTransferApi).toHaveBeenCalledWith({
        sourceWarehouseId: 'wh-1',
        destinationWarehouseId: 'wh-2',
        notes: 'Valid multi-line transfer test',
        items: [
          { productId: 'prod-1', quantity: 10 },
          { productId: 'prod-2', quantity: 25 },
        ],
      });
    });
  });

  it('rejects transfer if source and destination warehouses are identical', async () => {
    renderTransfersPage();

    await waitFor(() => {
      expect(screen.getByText('Execute Transfer')).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText('Execute Transfer'));

    // Change destination to match source (wh-1)
    const destSelect = screen.getByLabelText('Destination Facility') as HTMLSelectElement;
    fireEvent.change(destSelect, { target: { value: 'wh-1' } });

    fireEvent.click(screen.getByRole('button', { name: 'Execute Relocation' }));

    await waitFor(() => {
      expect(screen.getByText('Source and destination warehouse cannot be identical.')).toBeInTheDocument();
    });
    expect(transfersApi.executeTransferApi).not.toHaveBeenCalled();
  });
});
