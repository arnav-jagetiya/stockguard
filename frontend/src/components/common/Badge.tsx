import React from 'react';

export type BadgeVariant = 'success' | 'warning' | 'error' | 'danger' | 'info' | 'neutral' | 'accent' | 'default';

interface BadgeProps {
  children: React.ReactNode;
  variant?: BadgeVariant;
  className?: string;
  dot?: boolean;
}

export const Badge: React.FC<BadgeProps> = ({
  children,
  variant = 'neutral',
  className = '',
  dot = false,
}) => {
  const normalizedVariant = variant === 'danger' ? 'error' : variant === 'default' ? 'neutral' : variant;

  const variantStyles: Record<string, { bg: string; text: string; dot: string; border: string }> = {
    success: {
      bg: 'bg-emerald-50 dark:bg-emerald-500/10',
      text: 'text-emerald-700 dark:text-emerald-400',
      dot: 'bg-emerald-500 dark:bg-emerald-400',
      border: 'border-emerald-300 dark:border-emerald-500/20',
    },
    warning: {
      bg: 'bg-amber-50 dark:bg-amber-500/10',
      text: 'text-amber-700 dark:text-amber-400',
      dot: 'bg-amber-500 dark:bg-amber-400',
      border: 'border-amber-300 dark:border-amber-500/20',
    },
    error: {
      bg: 'bg-rose-50 dark:bg-rose-500/10',
      text: 'text-rose-700 dark:text-rose-400',
      dot: 'bg-rose-500 dark:bg-rose-400',
      border: 'border-rose-300 dark:border-rose-500/20',
    },
    info: {
      bg: 'bg-cyan-50 dark:bg-cyan-500/10',
      text: 'text-cyan-700 dark:text-cyan-400',
      dot: 'bg-cyan-500 dark:bg-cyan-400',
      border: 'border-cyan-300 dark:border-cyan-500/20',
    },
    accent: {
      bg: 'bg-indigo-50 dark:bg-indigo-500/10',
      text: 'text-indigo-700 dark:text-indigo-400',
      dot: 'bg-indigo-500 dark:bg-indigo-400',
      border: 'border-indigo-300 dark:border-indigo-500/20',
    },
    neutral: {
      bg: 'bg-slate-100 dark:bg-slate-800/60',
      text: 'text-slate-700 dark:text-slate-300',
      dot: 'bg-slate-500 dark:bg-slate-400',
      border: 'border-slate-300 dark:border-slate-700/60',
    },
  };

  const current = variantStyles[normalizedVariant] || variantStyles.neutral;

  return (
    <span
      className={`inline-flex items-center gap-1.5 px-2 py-0.5 text-xs font-medium rounded-full border ${current.bg} ${current.text} ${current.border} ${className}`}
    >
      {dot && <span className={`w-1.5 h-1.5 rounded-full ${current.dot}`} />}
      {children}
    </span>
  );
};
