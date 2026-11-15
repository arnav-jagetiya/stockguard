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
          className="block text-xs font-medium text-slate-300"
        >
          {label}
        </label>
      )}
      <select
        id={selectId}
        className={`w-full bg-[#0a101d] text-slate-100 text-sm rounded-lg px-3.5 py-2 border transition-colors outline-none cursor-pointer ${
          error
            ? 'border-rose-500/80 focus:border-rose-400'
            : 'border-slate-800 focus:border-cyan-500/70'
        } ${className}`}
        {...props}
      >
        {options.map((opt) => (
          <option key={opt.value} value={opt.value} className="bg-[#0b1220] text-slate-200">
            {opt.label}
          </option>
        ))}
      </select>
      {error && <p className="text-xs text-rose-400 font-medium">{error}</p>}
    </div>
  );
};
