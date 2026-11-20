import { describe, it, expect } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import React, { useRef } from 'react';
import { ToastProvider, useToast, useToasts } from '../ToastContext';
import type { ToastActions } from '../ToastContext';

describe('ToastContext & ToastProvider Architecture', () => {
  it('provides referentially stable toast actions across toast state updates', () => {
    let capturedActions1: ToastActions | null = null;
    let capturedActions2: ToastActions | null = null;
    let renderCount = 0;

    const TestConsumer: React.FC = () => {
      renderCount++;
      const toast = useToast();
      const firstRef = useRef<ToastActions | null>(null);
      if (!firstRef.current) {
        firstRef.current = toast;
        capturedActions1 = toast;
      } else {
        capturedActions2 = toast;
      }

      return (
        <div>
          <button data-testid="trigger-btn" onClick={() => toast.success('Operation succeeded')}>
            Trigger
          </button>
        </div>
      );
    };

    render(
      <ToastProvider>
        <TestConsumer />
      </ToastProvider>
    );

    expect(renderCount).toBe(1);
    expect(capturedActions1).not.toBeNull();

    // Trigger toast action
    act(() => {
      screen.getByTestId('trigger-btn').click();
    });

    // TestConsumer using useToast() should NOT re-render because actions context is decoupled from state context
    expect(renderCount).toBe(1);

    // If an action reference is captured, it must strictly equal the initial action reference
    if (capturedActions2) {
      expect(capturedActions1).toBe(capturedActions2);
    }
  });

  it('provides updated toast items to useToasts consumers while keeping useToast consumers unchanged', () => {
    const ActionsConsumer: React.FC = () => {
      const toast = useToast();
      return (
        <button data-testid="add-toast" onClick={() => toast.info('New alert message')}>
          Add Toast
        </button>
      );
    };

    const StateConsumer: React.FC = () => {
      const toasts = useToasts();
      return (
        <div data-testid="toast-count">{toasts.length}</div>
      );
    };

    render(
      <ToastProvider>
        <ActionsConsumer />
        <StateConsumer />
      </ToastProvider>
    );

    expect(screen.getByTestId('toast-count')).toHaveTextContent('0');

    act(() => {
      screen.getByTestId('add-toast').click();
    });

    expect(screen.getByTestId('toast-count')).toHaveTextContent('1');
  });
});
