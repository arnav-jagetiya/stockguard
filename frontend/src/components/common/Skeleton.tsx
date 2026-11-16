import React from 'react';

interface SkeletonProps {
  className?: string;
}

export const Skeleton: React.FC<SkeletonProps> = ({ className = '' }) => {
  return (
    <div
      className={`animate-pulse bg-[#E9EEE6] dark:bg-[#16221E] rounded-md ${className}`}
      aria-hidden="true"
    />
  );
};

export const TableSkeleton: React.FC<{ rows?: number; columns?: number; cols?: number }> = ({
  rows = 5,
  columns,
  cols,
}) => {
  const finalColumns = cols || columns || 5;
  return (
    <div className="space-y-3 p-4 animate-pulse" aria-label="Loading content...">
      <div className="grid grid-cols-5 gap-4 pb-3 border-b border-[#D9E1D7] dark:border-[#26362E]">
        {Array.from({ length: finalColumns }).map((_, i) => (
          <Skeleton key={i} className="h-4 w-3/4" />
        ))}
      </div>
      {Array.from({ length: rows }).map((_, r) => (
        <div key={r} className="grid grid-cols-5 gap-4 py-2.5 border-b border-[#E9EEE6] dark:border-[#26362E]/60">
          {Array.from({ length: finalColumns }).map((_, c) => (
            <Skeleton key={c} className="h-4 w-full" />
          ))}
        </div>
      ))}
    </div>
  );
};
