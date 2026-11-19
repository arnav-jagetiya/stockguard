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
  const errorId = inputId ? `${inputId}-error` : undefined;
  const helperId = inputId ? `${inputId}-helper` : undefined;

  return (
    <div className="w-full space-y-1.5">
      {label && (
        <label
          htmlFor={inputId}
          className="block text-xs font-semibold text-[#18231D] dark:text-[#EEF3EA]"
        >
          {label}
        </label>
      )}
      <div className="relative flex items-center">
        {icon && (
          <div className="absolute left-3 flex items-center pointer-events-none text-[#8A988E] dark:text-[#6C7D71]">
            {icon}
          </div>
        )}
        <input
          id={inputId}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : helperText ? helperId : undefined}
          className={`w-full bg-white dark:bg-[#111A17] text-[#18231D] dark:text-[#EEF3EA] caret-[#18231D] dark:caret-[#EEF3EA] text-sm rounded-lg py-2 border transition-colors outline-none placeholder:text-[#8A988E] dark:placeholder:text-[#6C7D71] shadow-xs disabled:opacity-50 disabled:cursor-not-allowed ${
            icon ? 'pl-9 pr-3.5' : 'px-3.5'
          } ${
            rightElement ? 'pr-10' : ''
          } ${
            error
              ? 'border-rose-500 focus:border-rose-500 focus:ring-2 focus:ring-rose-500/20'
              : 'border-[#D9E1D7] dark:border-[#26362E] focus:border-[#456B1F] dark:focus:border-[#C6F36B] focus:ring-2 focus:ring-[#456B1F]/20 dark:focus:ring-[#C6F36B]/20'
          } ${className}`}
          {...props}
        />
        {rightElement && (
          <div className="absolute right-3 flex items-center text-[#8A988E] dark:text-[#6C7D71]">
            {rightElement}
          </div>
        )}
      </div>
      {error && <p id={errorId} className="text-xs text-rose-600 dark:text-rose-400 font-medium">{error}</p>}
      {!error && helperText && (
        <p id={helperId} className="text-xs text-[#657369] dark:text-[#9AA99E]">{helperText}</p>
      )}
    </div>
  );
};
