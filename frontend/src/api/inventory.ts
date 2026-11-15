import { apiClient } from './client';
import type {
  AdjustStockRequest,
  InventoryBalance,
  InventoryTransaction,
  OpeningBalanceRequest,
  ReceiveStockRequest,
} from '../types/inventory';
import type { ReconciliationReport } from '../types/discrepancy';

export async function getBalancesApi(productId?: string, warehouseId?: string): Promise<InventoryBalance[]> {
  const q = new URLSearchParams();
  if (productId) q.append('productId', productId);
  if (warehouseId) q.append('warehouseId', warehouseId);
  const qs = q.toString() ? `?${q.toString()}` : '';
  return apiClient<InventoryBalance[]>(`/api/inventory/balances${qs}`);
}

export async function getTransactionsApi(limit: number = 50): Promise<InventoryTransaction[]> {
  return apiClient<InventoryTransaction[]>(`/api/inventory/transactions?limit=${limit}`);
}

export const listTransactionsApi = getTransactionsApi;

export async function receiveStockApi(data: ReceiveStockRequest): Promise<InventoryTransaction> {
  return apiClient<InventoryTransaction>('/api/inventory/receipts', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function adjustStockApi(data: AdjustStockRequest): Promise<InventoryTransaction> {
  return apiClient<InventoryTransaction>('/api/inventory/adjustments', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function initializeOpeningBalanceApi(data: OpeningBalanceRequest): Promise<InventoryTransaction> {
  return apiClient<InventoryTransaction>('/api/inventory/opening-balances', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export const recordOpeningBalanceApi = initializeOpeningBalanceApi;

export async function triggerReconciliationSweepApi(): Promise<ReconciliationReport> {
  return apiClient<ReconciliationReport>('/api/inventory/reconciliation/trigger', {
    method: 'POST',
  });
}
