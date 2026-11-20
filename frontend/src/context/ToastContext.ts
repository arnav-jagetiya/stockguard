import { createContext, useContext } from 'react';

export type ToastType = 'success' | 'error' | 'warning' | 'info';

export interface ToastItem {
  id: string;
  type: ToastType;
  title: string;
  description?: string;
  duration?: number;
}

export interface ToastActions {
  addToast: (toast: Omit<ToastItem, 'id'>) => void;
  removeToast: (id: string) => void;
  success: (title: string, description?: string) => void;
  error: (title: string, description?: string) => void;
  warning: (title: string, description?: string) => void;
  info: (title: string, description?: string) => void;
}

export interface ToastContextType extends ToastActions {
  toasts?: ToastItem[];
}

export const ToastActionsContext = createContext<ToastActions | undefined>(undefined);
export const ToastStateContext = createContext<ToastItem[]>([]);

export const ToastContext = ToastActionsContext;

export const useToast = (): ToastActions => {
  const context = useContext(ToastActionsContext);
  if (!context) {
    throw new Error('useToast must be used within a ToastProvider');
  }
  return context;
};

export const useToasts = (): ToastItem[] => {
  return useContext(ToastStateContext);
};

export { ToastProvider } from './ToastProvider';
