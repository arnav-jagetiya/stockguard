import React, { useState, useCallback, useMemo } from 'react';
import { CheckCircle2, AlertTriangle, AlertCircle, Info, X } from 'lucide-react';
import {
  ToastActionsContext,
  ToastStateContext,
  type ToastItem,
  type ToastActions,
} from './ToastContext';

export const ToastProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  const removeToast = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const addToast = useCallback(
    ({ type, title, description, duration = 4000 }: Omit<ToastItem, 'id'>) => {
      const id = `${Date.now()}-${Math.random().toString(36).substring(2, 9)}`;
      const newToast: ToastItem = { id, type, title, description, duration };

      setToasts((prev) => [...prev, newToast]);

      if (duration > 0) {
        setTimeout(() => {
          removeToast(id);
        }, duration);
      }
    },
    [removeToast]
  );

  const success = useCallback((title: string, description?: string) => {
    addToast({ type: 'success', title, description });
  }, [addToast]);

  const error = useCallback((title: string, description?: string) => {
    addToast({ type: 'error', title, description, duration: 6000 });
  }, [addToast]);

  const warning = useCallback((title: string, description?: string) => {
    addToast({ type: 'warning', title, description, duration: 5000 });
  }, [addToast]);

  const info = useCallback((title: string, description?: string) => {
    addToast({ type: 'info', title, description });
  }, [addToast]);

  const actions = useMemo<ToastActions>(
    () => ({
      addToast,
      removeToast,
      success,
      error,
      warning,
      info,
    }),
    [addToast, removeToast, success, error, warning, info]
  );

  return (
    <ToastActionsContext.Provider value={actions}>
      <ToastStateContext.Provider value={toasts}>
        {children}
        <div
          aria-live="polite"
          className="fixed bottom-4 right-4 z-50 flex flex-col gap-2 max-w-sm w-full pointer-events-none"
        >
          {toasts.map((toast) => {
            const typeConfig = {
              success: {
                icon: <CheckCircle2 className="w-5 h-5 text-[#456B1F] dark:text-[#C6F36B] shrink-0" />,
                border: 'border-[#456B1F]/30 dark:border-[#C6F36B]/30',
                bg: 'bg-white dark:bg-[#111A17]',
              },
              error: {
                icon: <AlertCircle className="w-5 h-5 text-rose-500 shrink-0" />,
                border: 'border-rose-500/30',
                bg: 'bg-white dark:bg-[#111A17]',
              },
              warning: {
                icon: <AlertTriangle className="w-5 h-5 text-amber-500 shrink-0" />,
                border: 'border-amber-500/30',
                bg: 'bg-white dark:bg-[#111A17]',
              },
              info: {
                icon: <Info className="w-5 h-5 text-[#147C68] dark:text-[#6CD8C0] shrink-0" />,
                border: 'border-[#147C68]/30 dark:border-[#6CD8C0]/30',
                bg: 'bg-white dark:bg-[#111A17]',
              },
            }[toast.type];

            return (
              <div
                key={toast.id}
                role="alert"
                className={`pointer-events-auto flex items-start gap-3 p-3.5 rounded-xl border shadow-lg backdrop-blur-md transition-all duration-200 transform translate-y-0 animate-in fade-in slide-in-from-top-3 sm:slide-in-from-bottom-3 ${typeConfig.border} ${typeConfig.bg}`}
              >
                {typeConfig.icon}
                <div className="flex-1 min-w-0">
                  <p className="text-sm font-semibold text-[#18231D] dark:text-[#EEF3EA]">{toast.title}</p>
                  {toast.description && (
                    <p className="text-xs text-[#657369] dark:text-[#9AA99E] mt-0.5 leading-relaxed">{toast.description}</p>
                  )}
                </div>
                <button
                  onClick={() => removeToast(toast.id)}
                  className="text-[#8A988E] hover:text-[#18231D] dark:hover:text-[#EEF3EA] p-0.5 rounded transition-colors cursor-pointer"
                  aria-label="Dismiss notification"
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
            );
          })}
        </div>
      </ToastStateContext.Provider>
    </ToastActionsContext.Provider>
  );
};
