import { apiClient } from './client';
import type { CreateTransferRequest, Transfer } from '../types/transfer';

export async function getTransfersApi(warehouseId?: string, limit: number = 50): Promise<Transfer[]> {
  const q = new URLSearchParams();
  if (warehouseId) q.append('warehouseId', warehouseId);
  q.append('limit', limit.toString());
  const qs = `?${q.toString()}`;
  return apiClient<Transfer[]>(`/api/transfers${qs}`);
}

export const listTransfersApi = getTransfersApi;

export async function createTransferApi(data: CreateTransferRequest): Promise<Transfer> {
  return apiClient<Transfer>('/api/transfers', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export const executeTransferApi = createTransferApi;
