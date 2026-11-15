import { apiClient } from './client';
import type { CreateWarehouseRequest, UpdateWarehouseRequest, Warehouse } from '../types/warehouse';

export async function getWarehousesApi(search?: string, activeOnly: boolean = false): Promise<Warehouse[]> {
  const q = new URLSearchParams();
  if (search) q.append('search', search);
  if (activeOnly) q.append('activeOnly', 'true');
  const qs = q.toString() ? `?${q.toString()}` : '';
  return apiClient<Warehouse[]>(`/api/warehouses${qs}`);
}

export const listWarehousesApi = getWarehousesApi;

export async function getWarehouseByIdApi(warehouseId: string): Promise<Warehouse> {
  return apiClient<Warehouse>(`/api/warehouses/${warehouseId}`);
}

export async function createWarehouseApi(data: CreateWarehouseRequest): Promise<Warehouse> {
  return apiClient<Warehouse>('/api/warehouses', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function updateWarehouseApi(warehouseId: string, data: UpdateWarehouseRequest): Promise<Warehouse> {
  return apiClient<Warehouse>(`/api/warehouses/${warehouseId}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

export async function deactivateWarehouseApi(warehouseId: string): Promise<{ message: string }> {
  return apiClient<{ message: string }>(`/api/warehouses/${warehouseId}`, {
    method: 'DELETE',
  });
}
