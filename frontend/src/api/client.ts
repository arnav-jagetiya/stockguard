/**
 * Authoritative typed API client for StockGuard platform.
 * Adheres strictly to backend security constraints:
 * - Sends Authorization: Bearer <token>
 * - Sends X-Organization-Id: <uuid> for org-scoped routes
 * - Sends X-StockGuard-CSRF: 1 on sensitive/state-changing requests
 * - Sends credentials: 'include' for HttpOnly refresh cookies
 * - Transparent 401 handling with single-token rotation retry
 */

export interface ApiErrorPayload {
  timestamp?: string;
  status?: number;
  error?: string;
  message: string;
  path?: string;
}

export class ApiError extends Error {
  status: number;
  payload?: ApiErrorPayload;

  constructor(status: number, message: string, payload?: ApiErrorPayload) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.payload = payload;
  }
}

/**
 * Checks if an error is a deliberate client-side request cancellation via AbortController.
 */
export function isAbortError(err: unknown): boolean {
  return (
    (err instanceof DOMException && err.name === 'AbortError') ||
    (err instanceof Error && err.name === 'AbortError')
  );
}

let activeAccessToken: string | null = null;
let activeOrganizationId: string | null = null;
let onUnauthorizedHandler: (() => Promise<boolean>) | null = null;

export const setAuthToken = (token: string | null) => {
  activeAccessToken = token;
};

export const getAuthToken = (): string | null => activeAccessToken;

export const setActiveOrganizationId = (orgId: string | null) => {
  activeOrganizationId = orgId;
};

export const getActiveOrganizationId = (): string | null => activeOrganizationId;

export const setOnUnauthorized = (handler: () => Promise<boolean>) => {
  onUnauthorizedHandler = handler;
};

export interface RequestOptions extends RequestInit {
  skipAuth?: boolean;
  skipOrgHeader?: boolean;
  idempotencyKey?: string;
}

export async function apiClient<T>(endpoint: string, options: RequestOptions = {}): Promise<T> {
  const url = endpoint.startsWith('/') ? endpoint : `/${endpoint}`;

  const headers = new Headers(options.headers || {});

  // Add JSON Content-Type if not set and body exists
  if (options.body && typeof options.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }

  // Add Authorization header
  if (!options.skipAuth && activeAccessToken) {
    headers.set('Authorization', `Bearer ${activeAccessToken}`);
  }

  // Add Organization Context header
  if (!options.skipOrgHeader && activeOrganizationId) {
    headers.set('X-Organization-Id', activeOrganizationId);
  }

  // Add CSRF Protection header
  headers.set('X-StockGuard-CSRF', '1');

  // Add optional Idempotency-Key
  if (options.idempotencyKey) {
    headers.set('Idempotency-Key', options.idempotencyKey);
  }

  const response = await fetch(url, {
    ...options,
    headers,
    credentials: 'include', // Ensures HttpOnly refresh cookie is sent
  });

  // Handle 401 with silent token refresh attempt
  if (response.status === 401 && !options.skipAuth && !url.includes('/api/auth/login') && !url.includes('/api/auth/refresh')) {
    if (onUnauthorizedHandler) {
      const refreshed = await onUnauthorizedHandler();
      if (refreshed && activeAccessToken) {
        // Retry original request once with new access token
        headers.set('Authorization', `Bearer ${activeAccessToken}`);
        const retryResponse = await fetch(url, {
          ...options,
          headers,
          credentials: 'include',
        });
        if (!retryResponse.ok) {
          const errData = await parseError(retryResponse);
          throw new ApiError(retryResponse.status, errData.message, errData);
        }
        if (retryResponse.status === 204) {
          return {} as T;
        }
        return retryResponse.json() as Promise<T>;
      }
    }
  }

  if (!response.ok) {
    const errData = await parseError(response);
    throw new ApiError(response.status, errData.message, errData);
  }

  if (response.status === 204) {
    return {} as T;
  }

  return response.json() as Promise<T>;
}

async function parseError(response: Response): Promise<ApiErrorPayload> {
  try {
    const data = await response.json();
    return {
      status: response.status,
      message: data.message || data.error || `HTTP ${response.status} Error`,
      error: data.error,
      path: data.path,
    };
  } catch {
    return {
      status: response.status,
      message: `Request failed with status ${response.status}`,
    };
  }
}
