import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import {
  apiClient,
  setAuthToken,
  setActiveOrganizationId,
  setOnUnauthorized,
  ApiError,
} from '../client';

describe('Authoritative API Client (client.ts)', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    setAuthToken(null);
    setActiveOrganizationId(null);
    setOnUnauthorized(async () => false);
    vi.restoreAllMocks();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('attaches mandatory CSRF protection header and credentials include', async () => {
    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ success: true }),
    });
    globalThis.fetch = mockFetch;

    await apiClient('/api/test');

    expect(mockFetch).toHaveBeenCalledTimes(1);
    const [url, options] = mockFetch.mock.calls[0];
    expect(url).toBe('/api/test');
    expect(options.credentials).toBe('include');

    const headers = options.headers as Headers;
    expect(headers.get('X-StockGuard-CSRF')).toBe('1');
  });

  it('injects Bearer authorization token and X-Organization-Id header', async () => {
    setAuthToken('mock-jwt-token-xyz');
    setActiveOrganizationId('550e8400-e29b-41d4-a716-446655440000');

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ data: 'ok' }),
    });
    globalThis.fetch = mockFetch;

    await apiClient('/api/products');

    const [, options] = mockFetch.mock.calls[0];
    const headers = options.headers as Headers;
    expect(headers.get('Authorization')).toBe('Bearer mock-jwt-token-xyz');
    expect(headers.get('X-Organization-Id')).toBe('550e8400-e29b-41d4-a716-446655440000');
  });

  it('throws structured ApiError on 4xx/5xx responses', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 403,
      json: async () => ({
        status: 403,
        error: 'Forbidden',
        message: 'Insufficient tenant privileges to access this resource',
        path: '/api/organizations/current/members',
      }),
    });

    await expect(apiClient('/api/organizations/current/members')).rejects.toThrow(ApiError);
    try {
      await apiClient('/api/organizations/current/members');
    } catch (err) {
      const apiErr = err as ApiError;
      expect(apiErr.status).toBe(403);
      expect(apiErr.message).toBe('Insufficient tenant privileges to access this resource');
    }
  });

  it('performs transparent single-token retry on 401 response', async () => {
    setAuthToken('stale-token');

    let callCount = 0;
    globalThis.fetch = vi.fn().mockImplementation(() => {
      callCount++;
      if (callCount === 1) {
        return Promise.resolve({
          ok: false,
          status: 401,
          json: async () => ({ message: 'JWT expired' }),
        });
      }
      return Promise.resolve({
        ok: true,
        status: 200,
        json: async () => ({ recovered: true }),
      });
    });

    // Mock successful refresh handler that updates the token
    setOnUnauthorized(async () => {
      setAuthToken('fresh-rotated-token');
      return true;
    });

    const result = await apiClient<{ recovered: boolean }>('/api/dashboard/summary');

    expect(result.recovered).toBe(true);
    expect(callCount).toBe(2);
  });
});
