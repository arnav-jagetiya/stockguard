import React from 'react';

export const Table: React.FC<{ children: React.ReactNode; className?: string }> = ({
  children,
  className = '',
}) => {
  return (
    <div className={`w-full overflow-x-auto rounded-xl border border-[#D9E1D7] dark:border-[#26362E] bg-white dark:bg-[#111A17] shadow-xs ${className}`}>
      <table className="w-full text-left border-collapse text-sm">{children}</table>
    </div>
  );
};

export const TableHead: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <thead className="bg-[#F0F4EE] dark:bg-[#16221E] border-b border-[#D9E1D7] dark:border-[#26362E] text-[11px] uppercase font-semibold font-mono tracking-wider text-[#657369] dark:text-[#9AA99E]">
    {children}
  </thead>
);

export const TableHeader: React.FC<{
  children: React.ReactNode;
  className?: string;
}> = ({ children, className = '' }) => (
  <th className={`px-4 py-3 text-[11px] font-semibold text-[#657369] dark:text-[#9AA99E] ${className}`}>
    {children}
  </th>
);

export const TableBody: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <tbody className="divide-y divide-[#D9E1D7]/60 dark:divide-[#26362E]/60 text-[#18231D] dark:text-[#EEF3EA]">{children}</tbody>
);

export const TableRow: React.FC<{
  children: React.ReactNode;
  onClick?: () => void;
  className?: string;
}> = ({ children, onClick, className = '' }) => (
  <tr
    onClick={onClick}
    className={`transition-colors duration-100 ${
      onClick ? 'cursor-pointer hover:bg-[#F9FAF7] dark:hover:bg-[#182621]' : 'hover:bg-[#F9FAF7]/70 dark:hover:bg-[#182621]/50'
    } ${className}`}
  >
    {children}
  </tr>
);

export const TableCell: React.FC<{
  children: React.ReactNode;
  className?: string;
}> = ({ children, className = '' }) => (
  <td className={`px-4 py-3 text-xs text-[#18231D] dark:text-[#EEF3EA] ${className}`}>{children}</td>
);
