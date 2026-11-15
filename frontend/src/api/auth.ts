import { apiClient } from './client';
import type { AuthTokenResponse, SwitchOrgResponse, UserMeResponse } from '../types/auth';

export async function loginApi(email: string, password: string): Promise<AuthTokenResponse> {
  return apiClient<AuthTokenResponse>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
}

export async function refreshApi(): Promise<AuthTokenResponse> {
  return apiClient<AuthTokenResponse>('/api/auth/refresh', {
    method: 'POST',
  });
}

export async function logoutApi(): Promise<{ message: string }> {
  return apiClient<{ message: string }>('/api/auth/logout', {
    method: 'POST',
  });
}

export async function switchOrgApi(organizationId: string): Promise<SwitchOrgResponse> {
  return apiClient<SwitchOrgResponse>('/api/auth/switch-org', {
    method: 'POST',
    body: JSON.stringify({ targetOrganizationId: organizationId }),
  });
}

export async function getMeApi(): Promise<UserMeResponse> {
  return apiClient<UserMeResponse>('/api/auth/me');
}
