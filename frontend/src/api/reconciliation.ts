import { apiClient } from './client';
import type {
  Discrepancy,
  DiscrepancyPageResponse,
  DiscrepancyStatus,
  ReconciliationReport,
  UpdateDiscrepancyRequest,
} from '../types/discrepancy';

export interface DiscrepancyFilterParams {
  status?: DiscrepancyStatus;
  productId?: string;
  warehouseId?: string;
  assignedToUserId?: string;
  page?: number;
  size?: number;
  limit?: number;
  offset?: number;
  signal?: AbortSignal;
}

export async function getDiscrepanciesApi(params: DiscrepancyFilterParams = {}): Promise<DiscrepancyPageResponse> {
  const q = new URLSearchParams();
  if (params.status) q.append('status', params.status);
  if (params.productId) q.append('productId', params.productId);
  if (params.warehouseId) q.append('warehouseId', params.warehouseId);
  if (params.assignedToUserId) q.append('assignedToUserId', params.assignedToUserId);

  const limit = params.limit !== undefined ? params.limit : params.size !== undefined ? params.size : 50;
  const offset = params.offset !== undefined ? params.offset : params.page !== undefined ? params.page * limit : 0;

  q.append('limit', limit.toString());
  q.append('offset', offset.toString());

  const qs = `?${q.toString()}`;
  return apiClient<DiscrepancyPageResponse>(`/api/inventory/reconciliation/discrepancies${qs}`, {
    signal: params.signal,
  });
}

export const listDiscrepanciesApi = getDiscrepanciesApi;

export async function getDiscrepancyByIdApi(id: string): Promise<Discrepancy> {
  return apiClient<Discrepancy>(`/api/inventory/reconciliation/discrepancies/${id}`);
}

export const getDiscrepancyApi = getDiscrepancyByIdApi;

export async function updateDiscrepancyApi(id: string, data: UpdateDiscrepancyRequest): Promise<Discrepancy> {
  return apiClient<Discrepancy>(`/api/inventory/reconciliation/discrepancies/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(data),
  });
}

export async function triggerReconciliationSweepApi(): Promise<ReconciliationReport> {
  return apiClient<ReconciliationReport>('/api/inventory/reconciliation/trigger', {
    method: 'POST',
  });
}

export const triggerReconciliationApi = triggerReconciliationSweepApi;
