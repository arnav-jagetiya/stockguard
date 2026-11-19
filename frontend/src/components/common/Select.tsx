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
  const errorId = selectId ? `${selectId}-error` : undefined;

  return (
    <div className="w-full space-y-1.5">
      {label && (
        <label
          htmlFor={selectId}
          className="block text-xs font-semibold text-[#18231D] dark:text-[#EEF3EA]"
        >
          {label}
        </label>
      )}
      <select
        id={selectId}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        className={`w-full bg-white dark:bg-[#111A17] text-[#18231D] dark:text-[#EEF3EA] text-sm rounded-lg px-3.5 py-2 border transition-colors outline-none cursor-pointer shadow-xs disabled:opacity-50 disabled:cursor-not-allowed ${
          error
            ? 'border-rose-500 focus:border-rose-500 focus:ring-2 focus:ring-rose-500/20'
            : 'border-[#D9E1D7] dark:border-[#26362E] focus:border-[#456B1F] dark:focus:border-[#C6F36B] focus:ring-2 focus:ring-[#456B1F]/20 dark:focus:ring-[#C6F36B]/20'
        } ${className}`}
        {...props}
      >
        {options.map((opt) => (
          <option key={opt.value} value={opt.value} className="bg-white dark:bg-[#111A17] text-[#18231D] dark:text-[#EEF3EA]">
            {opt.label}
          </option>
        ))}
      </select>
      {error && <p id={errorId} className="text-xs text-rose-600 dark:text-rose-400 font-medium">{error}</p>}
    </div>
  );
};
