import React from 'react';

interface CardProps {
  children: React.ReactNode;
  className?: string;
  glow?: boolean;
  raised?: boolean;
  onClick?: () => void;
}

export const Card: React.FC<CardProps> = ({
  children,
  className = '',
  glow = false,
  raised = false,
  onClick,
}) => {
  return (
    <div
      onClick={onClick}
      className={`rounded-xl border transition-all duration-200 ${
        raised
          ? 'bg-[#E9EEE6] dark:bg-[#1B2922] border-[#D9E1D7] dark:border-[#26362E]'
          : 'bg-white dark:bg-[#111A17] border-[#D9E1D7] dark:border-[#26362E]'
      } shadow-xs p-5 ${
        glow
          ? 'shadow-[0_0_25px_rgba(198,243,107,0.12)] border-[#456B1F]/40 dark:border-[#C6F36B]/40'
          : 'hover:border-[#657369]/30 dark:hover:border-[#9AA99E]/30'
      } ${onClick ? 'cursor-pointer hover:bg-[#F9FAF7] dark:hover:bg-[#182621]' : ''} ${className}`}
    >
      {children}
    </div>
  );
};
