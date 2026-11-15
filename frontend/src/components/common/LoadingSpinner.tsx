import React from 'react';

export interface LoadingSpinnerProps {
  message?: string;
  text?: string;
  size?: 'sm' | 'md' | 'lg';
  className?: string;
}

export const LoadingSpinner: React.FC<LoadingSpinnerProps> = ({
  message,
  text,
  size = 'md',
  className = '',
}) => {
  const displayMessage = text || message || 'Loading operational telemetry...';
  const sizeMap = {
    sm: 'w-4 h-4',
    md: 'w-7 h-7',
    lg: 'w-10 h-10',
  };

  return (
    <div className={`flex flex-col items-center justify-center p-12 space-y-3 ${className}`}>
      <div className={`relative ${sizeMap[size]}`}>
        <div className="w-full h-full rounded-full border-2 border-slate-800" />
        <div className="absolute top-0 left-0 w-full h-full rounded-full border-2 border-cyan-400 border-t-transparent animate-spin" />
      </div>
      {displayMessage && <p className="text-xs font-medium text-slate-400 animate-pulse">{displayMessage}</p>}
    </div>
  );
};
