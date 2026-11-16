import React from 'react';

export const Table: React.FC<{ children: React.ReactNode; className?: string }> = ({
  children,
  className = '',
}) => {
  return (
    <div className={`w-full overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-800/80 bg-white dark:bg-[#0c1220]/60 shadow-xs dark:shadow-none ${className}`}>
      <table className="w-full text-left border-collapse text-sm">{children}</table>
    </div>
  );
};

export const TableHead: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <thead className="bg-slate-50 dark:bg-[#080d18] border-b border-slate-200 dark:border-slate-800 text-xs uppercase font-medium tracking-wider text-slate-500 dark:text-slate-400">
    {children}
  </thead>
);

export const TableHeader: React.FC<{
  children: React.ReactNode;
  className?: string;
}> = ({ children, className = '' }) => (
  <th className={`px-4 py-3 text-xs font-semibold text-slate-600 dark:text-slate-400 ${className}`}>
    {children}
  </th>
);

export const TableBody: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <tbody className="divide-y divide-slate-100 dark:divide-slate-800/60 text-slate-800 dark:text-slate-200">{children}</tbody>
);

export const TableRow: React.FC<{
  children: React.ReactNode;
  onClick?: () => void;
  className?: string;
}> = ({ children, onClick, className = '' }) => (
  <tr
    onClick={onClick}
    className={`transition-colors duration-100 ${
      onClick ? 'cursor-pointer hover:bg-slate-50 dark:hover:bg-slate-800/50' : 'hover:bg-slate-50/80 dark:hover:bg-slate-850/30'
    } ${className}`}
  >
    {children}
  </tr>
);

export const TableCell: React.FC<{
  children: React.ReactNode;
  className?: string;
}> = ({ children, className = '' }) => (
  <td className={`px-4 py-3.5 text-xs text-slate-700 dark:text-slate-300 ${className}`}>{children}</td>
);
