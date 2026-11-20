import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import React from 'react';
import { AuthProvider, useAuth } from '../AuthContext';
import * as authApi from '../../api/auth';

vi.mock('../../api/auth', () => ({
  silentRefreshApi: vi.fn().mockRejectedValue(new Error('No active session')),
  demoLoginApi: vi.fn().mockResolvedValue({
    accessToken: 'demo-token',
    user: { id: 'demo-user', email: 'demo@stockguard.dev', fullName: 'Demo Visitor' },
    availableOrganizations: [
      { id: 'org-acme', name: 'Acme Logistics', role: 'VIEWER' },
    ],
  }),
  loginApi: vi.fn(),
  registerApi: vi.fn(),
  logoutApi: vi.fn().mockResolvedValue(undefined),
  setOnUnauthorized: vi.fn(),
}));

describe('AuthProvider Method Stability', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('maintains referentially stable authentication methods across renders', async () => {
    let capturedDemoLogin1: ((signal?: AbortSignal) => Promise<void>) | null = null;
    let capturedDemoLogin2: ((signal?: AbortSignal) => Promise<void>) | null = null;
    let capturedLogout1: (() => Promise<void>) | null = null;
    let capturedLogout2: (() => Promise<void>) | null = null;

    const Consumer: React.FC<{ renderKey: number }> = () => {
      const { loginDemoVisitor, logout } = useAuth();
      if (!capturedDemoLogin1) {
        capturedDemoLogin1 = loginDemoVisitor;
        capturedLogout1 = logout;
      } else {
        capturedDemoLogin2 = loginDemoVisitor;
        capturedLogout2 = logout;
      }

      return <div data-testid="ready">Ready</div>;
    };

    let renderResult: ReturnType<typeof render>;
    await act(async () => {
      renderResult = render(
        <AuthProvider>
          <Consumer renderKey={1} />
        </AuthProvider>
      );
    });

    expect(screen.getByTestId('ready')).toBeInTheDocument();

    // Force re-render with new prop
    await act(async () => {
      renderResult.rerender(
        <AuthProvider>
          <Consumer renderKey={2} />
        </AuthProvider>
      );
    });

    expect(capturedDemoLogin1).not.toBeNull();
    expect(capturedDemoLogin2).not.toBeNull();
    expect(capturedDemoLogin1).toBe(capturedDemoLogin2);
    expect(capturedLogout1).toBe(capturedLogout2);
  });

  it('passes AbortSignal to demoLoginApi when loginDemoVisitor is called with signal', async () => {
    let callDemo: ((signal?: AbortSignal) => Promise<void>) | null = null;

    const Consumer: React.FC = () => {
      const { loginDemoVisitor } = useAuth();
      callDemo = loginDemoVisitor;
      return <div data-testid="ready">Ready</div>;
    };

    await act(async () => {
      render(
        <AuthProvider>
          <Consumer />
        </AuthProvider>
      );
    });

    const controller = new AbortController();
    await act(async () => {
      await callDemo?.(controller.signal);
    });

    expect(authApi.demoLoginApi).toHaveBeenCalledWith(controller.signal);
  });
});
