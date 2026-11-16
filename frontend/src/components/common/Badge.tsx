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
      bg: 'bg-[#E9EEE6] dark:bg-[#C6F36B]/10',
      text: 'text-[#456B1F] dark:text-[#C6F36B]',
      dot: 'bg-[#456B1F] dark:bg-[#C6F36B]',
      border: 'border-[#D9E1D7] dark:border-[#C6F36B]/30',
    },
    warning: {
      bg: 'bg-amber-50 dark:bg-amber-500/10',
      text: 'text-amber-800 dark:text-amber-400',
      dot: 'bg-amber-500 dark:bg-amber-400',
      border: 'border-amber-300 dark:border-amber-500/30',
    },
    error: {
      bg: 'bg-rose-50 dark:bg-rose-500/10',
      text: 'text-rose-800 dark:text-rose-400',
      dot: 'bg-rose-500 dark:bg-rose-400',
      border: 'border-rose-300 dark:border-rose-500/30',
    },
    info: {
      bg: 'bg-teal-50 dark:bg-[#6CD8C0]/10',
      text: 'text-teal-800 dark:text-[#6CD8C0]',
      dot: 'bg-[#147C68] dark:bg-[#6CD8C0]',
      border: 'border-teal-300 dark:border-[#6CD8C0]/30',
    },
    accent: {
      bg: 'bg-indigo-50 dark:bg-indigo-500/10',
      text: 'text-indigo-800 dark:text-indigo-400',
      dot: 'bg-indigo-500 dark:bg-indigo-400',
      border: 'border-indigo-300 dark:border-indigo-500/30',
    },
    neutral: {
      bg: 'bg-[#E9EEE6] dark:bg-[#1B2922]',
      text: 'text-[#18231D] dark:text-[#EEF3EA]',
      dot: 'bg-[#657369] dark:bg-[#9AA99E]',
      border: 'border-[#D9E1D7] dark:border-[#26362E]',
    },
  };

  const current = variantStyles[normalizedVariant] || variantStyles.neutral;

  return (
    <span
      className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded-md text-[11px] font-medium border font-mono tracking-tight ${current.bg} ${current.text} ${current.border} ${className}`}
    >
      {dot && (
        <span
          className={`w-1.5 h-1.5 rounded-full ${current.dot} shrink-0 animate-pulse`}
          aria-hidden="true"
        />
      )}
      {children}
    </span>
  );
};
