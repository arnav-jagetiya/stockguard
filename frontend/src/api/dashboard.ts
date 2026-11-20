import { apiClient } from './client';
import type { DashboardSummaryResponse, WarehouseDashboardResponse } from '../types/dashboard';

export async function getDashboardSummaryApi(signal?: AbortSignal): Promise<DashboardSummaryResponse> {
  return apiClient<DashboardSummaryResponse>('/api/dashboard/summary', { signal });
}

export async function getWarehouseDashboardApi(warehouseId: string, signal?: AbortSignal): Promise<WarehouseDashboardResponse> {
  return apiClient<WarehouseDashboardResponse>(`/api/dashboard/warehouses/${warehouseId}`, { signal });
}
