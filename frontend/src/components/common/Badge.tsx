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
      bg: 'bg-emerald-500/10',
      text: 'text-emerald-400',
      dot: 'bg-emerald-400',
      border: 'border-emerald-500/20',
    },
    warning: {
      bg: 'bg-amber-500/10',
      text: 'text-amber-400',
      dot: 'bg-amber-400',
      border: 'border-amber-500/20',
    },
    error: {
      bg: 'bg-rose-500/10',
      text: 'text-rose-400',
      dot: 'bg-rose-400',
      border: 'border-rose-500/20',
    },
    info: {
      bg: 'bg-cyan-500/10',
      text: 'text-cyan-400',
      dot: 'bg-cyan-400',
      border: 'border-cyan-500/20',
    },
    accent: {
      bg: 'bg-indigo-500/10',
      text: 'text-indigo-400',
      dot: 'bg-indigo-400',
      border: 'border-indigo-500/20',
    },
    neutral: {
      bg: 'bg-slate-800/60',
      text: 'text-slate-300',
      dot: 'bg-slate-400',
      border: 'border-slate-700/60',
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
