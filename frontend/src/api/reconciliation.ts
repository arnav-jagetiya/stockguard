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
  page?: number;
  size?: number;
  limit?: number;
  offset?: number;
}

export async function getDiscrepanciesApi(params: DiscrepancyFilterParams = {}): Promise<DiscrepancyPageResponse> {
  const q = new URLSearchParams();
  if (params.status) q.append('status', params.status);
  if (params.productId) q.append('productId', params.productId);
  if (params.warehouseId) q.append('warehouseId', params.warehouseId);
  const pageNum = params.page !== undefined ? params.page : params.offset !== undefined && params.limit ? Math.floor(params.offset / params.limit) : undefined;
  const pageSize = params.size !== undefined ? params.size : params.limit !== undefined ? params.limit : undefined;
  if (pageNum !== undefined) q.append('page', pageNum.toString());
  if (pageSize !== undefined) q.append('size', pageSize.toString());
  const qs = q.toString() ? `?${q.toString()}` : '';
  return apiClient<DiscrepancyPageResponse>(`/api/discrepancies${qs}`);
}

export const listDiscrepanciesApi = getDiscrepanciesApi;

export async function getDiscrepancyByIdApi(id: string): Promise<Discrepancy> {
  return apiClient<Discrepancy>(`/api/discrepancies/${id}`);
}

export const getDiscrepancyApi = getDiscrepancyByIdApi;

export async function updateDiscrepancyApi(id: string, data: UpdateDiscrepancyRequest): Promise<Discrepancy> {
  return apiClient<Discrepancy>(`/api/discrepancies/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(data),
  });
}

export async function triggerReconciliationSweepApi(): Promise<ReconciliationReport> {
  return apiClient<ReconciliationReport>('/api/inventory/reconcile', {
    method: 'POST',
  });
}

export const triggerReconciliationApi = triggerReconciliationSweepApi;
