import { apiClient } from './client';
import type { DashboardSummaryResponse, WarehouseDashboardResponse } from '../types/dashboard';

export async function getDashboardSummaryApi(): Promise<DashboardSummaryResponse> {
  return apiClient<DashboardSummaryResponse>('/api/dashboard/summary');
}

export async function getWarehouseDashboardApi(warehouseId: string): Promise<WarehouseDashboardResponse> {
  return apiClient<WarehouseDashboardResponse>(`/api/dashboard/warehouses/${warehouseId}`);
}
