import React from 'react';

interface InputProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label?: string;
  error?: string;
  helperText?: string;
  rightElement?: React.ReactNode;
  icon?: React.ReactNode;
}

export const Input: React.FC<InputProps> = ({
  label,
  error,
  helperText,
  rightElement,
  icon,
  className = '',
  id,
  ...props
}) => {
  const inputId = id || (label ? label.toLowerCase().replace(/\s+/g, '-') : undefined);

  return (
    <div className="w-full space-y-1.5">
      {label && (
        <label
          htmlFor={inputId}
          className="block text-xs font-medium text-slate-700 dark:text-slate-300"
        >
          {label}
        </label>
      )}
      <div className="relative flex items-center">
        {icon && (
          <div className="absolute left-3 flex items-center pointer-events-none text-slate-400 dark:text-slate-500">
            {icon}
          </div>
        )}
        <input
          id={inputId}
          className={`w-full bg-white dark:bg-[#0a101d] text-slate-900 dark:text-slate-100 text-sm rounded-lg py-2 border transition-colors outline-none placeholder:text-slate-400 dark:placeholder:text-slate-500 shadow-xs ${
            icon ? 'pl-9 pr-3.5' : 'px-3.5'
          } ${
            rightElement ? 'pr-10' : ''
          } ${
            error
              ? 'border-rose-500 focus:border-rose-500 focus:ring-1 focus:ring-rose-500/30'
              : 'border-slate-300 dark:border-slate-800 focus:border-cyan-500 focus:ring-1 focus:ring-cyan-500/20'
          } ${className}`}
          {...props}
        />
        {rightElement && (
          <div className="absolute right-3 flex items-center text-slate-400 dark:text-slate-500">
            {rightElement}
          </div>
        )}
      </div>
      {error && <p className="text-xs text-rose-500 dark:text-rose-400 font-medium">{error}</p>}
      {!error && helperText && (
        <p className="text-xs text-slate-500 dark:text-slate-400">{helperText}</p>
      )}
    </div>
  );
};
