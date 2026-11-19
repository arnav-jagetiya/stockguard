import { apiClient } from './client';
import type { AuditLogPageResponse } from '../types/audit';

export interface AuditLogFilterParams {
  action?: string;
  entityType?: string;
  entityId?: string;
  actorUserId?: string;
  startDate?: string;
  endDate?: string;
  page?: number;
  size?: number;
  signal?: AbortSignal;
}

export async function getAuditLogsApi(params: AuditLogFilterParams = {}): Promise<AuditLogPageResponse> {
  const q = new URLSearchParams();
  if (params.action) q.append('action', params.action);
  if (params.entityType) q.append('entityType', params.entityType);
  if (params.entityId) q.append('entityId', params.entityId);
  if (params.actorUserId) q.append('actorUserId', params.actorUserId);
  if (params.startDate) q.append('startDate', params.startDate);
  if (params.endDate) q.append('endDate', params.endDate);
  if (params.page !== undefined) q.append('page', params.page.toString());
  if (params.size !== undefined) q.append('size', params.size.toString());
  const qs = q.toString() ? `?${q.toString()}` : '';
  return apiClient<AuditLogPageResponse>(`/api/audit-logs${qs}`, { signal: params.signal });
}
