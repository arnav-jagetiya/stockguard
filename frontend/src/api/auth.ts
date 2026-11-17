import { apiClient } from './client';
import type { AuthTokenResponse, RegisterRequest, SwitchOrgResponse, UserMeResponse } from '../types/auth';

export async function loginApi(email: string, password: string): Promise<AuthTokenResponse> {
  return apiClient<AuthTokenResponse>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify({ email, password }),
  });
}

export async function registerApi(data: RegisterRequest): Promise<AuthTokenResponse> {
  return apiClient<AuthTokenResponse>('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify(data),
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

export interface DemoCredentialsResponse {
  available: boolean;
  email: string;
  password?: string;
}

export async function getDemoCredentialsApi(): Promise<DemoCredentialsResponse> {
  return apiClient<DemoCredentialsResponse>('/api/auth/demo-credentials');
}

export async function demoLoginApi(): Promise<AuthTokenResponse> {
  return apiClient<AuthTokenResponse>('/api/auth/demo-login', {
    method: 'POST',
  });
}
