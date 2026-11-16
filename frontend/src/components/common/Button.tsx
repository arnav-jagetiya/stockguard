import React from 'react';

export type ButtonVariant = 'primary' | 'secondary' | 'outline' | 'danger' | 'ghost';
export type ButtonSize = 'sm' | 'md' | 'lg';

export interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  size?: ButtonSize;
  isLoading?: boolean;
  loading?: boolean;
  icon?: React.ReactNode;
}

export const Button: React.FC<ButtonProps> = ({
  children,
  variant = 'primary',
  size = 'md',
  isLoading = false,
  loading = false,
  icon,
  className = '',
  disabled,
  ...props
}) => {
  const activeLoading = isLoading || loading;
  const sizeStyles: Record<ButtonSize, string> = {
    sm: 'px-3 py-1.5 text-xs rounded-md gap-1.5',
    md: 'px-4 py-2 text-sm rounded-lg gap-2',
    lg: 'px-5 py-2.5 text-base rounded-xl gap-2.5',
  };

  const variantStyles: Record<ButtonVariant, string> = {
    primary:
      'bg-[#456B1F] hover:bg-[#527E26] text-white dark:bg-[#C6F36B] dark:hover:bg-[#D4F68D] dark:text-[#0B100F] font-semibold border border-[#456B1F] dark:border-[#C6F36B] shadow-xs hover:shadow-[0_0_15px_rgba(69,107,31,0.25)] dark:hover:shadow-[0_0_20px_rgba(198,243,107,0.35)]',
    secondary:
      'bg-[#E9EEE6] hover:bg-[#DEE5DA] text-[#18231D] dark:bg-[#1B2922] dark:hover:bg-[#22332B] dark:text-[#EEF3EA] font-medium border border-[#D9E1D7] dark:border-[#26362E]',
    outline:
      'bg-transparent hover:bg-[#E9EEE6] dark:hover:bg-[#1B2922] text-[#18231D] dark:text-[#EEF3EA] border border-[#D9E1D7] dark:border-[#26362E]',
    danger:
      'bg-rose-50 dark:bg-rose-950/20 hover:bg-rose-100 dark:hover:bg-rose-900/30 text-rose-700 dark:text-rose-400 font-medium border border-rose-300 dark:border-rose-900/40',
    ghost:
      'bg-transparent hover:bg-[#E9EEE6]/80 dark:hover:bg-[#1B2922]/80 text-[#657369] dark:text-[#9AA99E] hover:text-[#18231D] dark:hover:text-[#EEF3EA] font-medium border border-transparent',
  };

  return (
    <button
      disabled={disabled || activeLoading}
      className={`inline-flex items-center justify-center transition-all duration-150 cursor-pointer disabled:cursor-not-allowed disabled:opacity-50 select-none ${sizeStyles[size]} ${variantStyles[variant]} ${className}`}
      {...props}
    >
      {activeLoading ? (
        <svg
          className="animate-spin h-4 w-4 text-current"
          xmlns="http://www.w3.org/2000/svg"
          fill="none"
          viewBox="0 0 24 24"
        >
          <circle
            className="opacity-25"
            cx="12"
            cy="12"
            r="10"
            stroke="currentColor"
            strokeWidth="4"
          />
          <path
            className="opacity-75"
            fill="currentColor"
            d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"
          />
        </svg>
      ) : icon ? (
        <span className="shrink-0">{icon}</span>
      ) : null}
      {children}
    </button>
  );
};
