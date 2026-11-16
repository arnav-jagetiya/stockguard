import React from 'react';

interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  label?: string;
  error?: string;
  options: { value: string; label: string }[];
}

export const Select: React.FC<SelectProps> = ({
  label,
  error,
  options,
  className = '',
  id,
  ...props
}) => {
  const selectId = id || (label ? label.toLowerCase().replace(/\s+/g, '-') : undefined);

  return (
    <div className="w-full space-y-1.5">
      {label && (
        <label
          htmlFor={selectId}
          className="block text-xs font-medium text-slate-700 dark:text-slate-300"
        >
          {label}
        </label>
      )}
      <select
        id={selectId}
        className={`w-full bg-white dark:bg-[#0a101d] text-slate-900 dark:text-slate-100 text-sm rounded-lg px-3.5 py-2 border transition-colors outline-none cursor-pointer shadow-xs ${
          error
            ? 'border-rose-500 focus:border-rose-500'
            : 'border-slate-300 dark:border-slate-800 focus:border-cyan-500'
        } ${className}`}
        {...props}
      >
        {options.map((opt) => (
          <option key={opt.value} value={opt.value} className="bg-white dark:bg-[#0b1220] text-slate-900 dark:text-slate-200">
            {opt.label}
          </option>
        ))}
      </select>
      {error && <p className="text-xs text-rose-500 dark:text-rose-400 font-medium">{error}</p>}
    </div>
  );
};
