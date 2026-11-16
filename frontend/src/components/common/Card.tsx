import React from 'react';

interface CardProps {
  children: React.ReactNode;
  className?: string;
  glow?: boolean;
  onClick?: () => void;
}

export const Card: React.FC<CardProps> = ({
  children,
  className = '',
  glow = false,
  onClick,
}) => {
  return (
    <div
      onClick={onClick}
      className={`rounded-xl bg-white dark:bg-[#0f172a]/80 border border-slate-200 dark:border-slate-800/80 shadow-xs dark:shadow-none p-5 transition-all duration-200 ${
        glow
          ? 'shadow-[0_0_30px_rgba(6,182,212,0.12)] border-cyan-500/40 dark:border-cyan-500/20'
          : 'hover:border-slate-300 dark:hover:border-slate-700/80'
      } ${onClick ? 'cursor-pointer hover:bg-slate-50 dark:hover:bg-[#131d35]' : ''} ${className}`}
    >
      {children}
    </div>
  );
};
