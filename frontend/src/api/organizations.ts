import { apiClient } from './client';
import type { AddMemberRequest, OrganizationMember, UpdateMemberRoleRequest } from '../types/organization';

export async function getMembershipsApi(): Promise<OrganizationMember[]> {
  return apiClient<OrganizationMember[]>('/api/organizations/current/memberships');
}

export async function addMemberApi(data: AddMemberRequest): Promise<OrganizationMember> {
  return apiClient<OrganizationMember>('/api/organizations/current/members', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function updateMemberRoleApi(targetUserId: string, data: UpdateMemberRoleRequest): Promise<OrganizationMember> {
  return apiClient<OrganizationMember>(`/api/organizations/current/members/${targetUserId}/role`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

export async function deactivateMemberApi(targetUserId: string): Promise<{ message: string }> {
  return apiClient<{ message: string }>(`/api/organizations/current/members/${targetUserId}`, {
    method: 'DELETE',
  });
}
