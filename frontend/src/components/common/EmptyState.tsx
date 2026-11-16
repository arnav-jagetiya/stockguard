import React from 'react';
import { Button } from './Button';

export interface EmptyStateProps {
  icon?: React.ReactNode | React.ComponentType<{ className?: string }>;
  title: string;
  description: string;
  actionLabel?: string;
  onAction?: () => void;
  action?: React.ReactNode;
}

export const EmptyState: React.FC<EmptyStateProps> = ({
  icon,
  title,
  description,
  actionLabel,
  onAction,
  action,
}) => {
  const renderIcon = () => {
    if (!icon) return null;
    if (React.isValidElement(icon)) return icon;
    if (typeof icon === 'function') {
      const IconComponent = icon as React.ComponentType<{ className?: string }>;
      return <IconComponent className="w-8 h-8" />;
    }
    return null;
  };

  return (
    <div className="flex flex-col items-center justify-center p-12 text-center border border-dashed border-[#D9E1D7] dark:border-[#26362E] rounded-2xl bg-[#F9FAF7] dark:bg-[#111A17] my-4">
      {icon && (
        <div className="p-3.5 mb-4 text-[#456B1F] dark:text-[#C6F36B] bg-[#E9EEE6] dark:bg-[#16221E] rounded-2xl border border-[#D9E1D7] dark:border-[#26362E] shadow-inner">
          {renderIcon()}
        </div>
      )}
      <h3 className="text-sm font-semibold text-[#18231D] dark:text-[#EEF3EA]">{title}</h3>
      <p className="mt-1 text-xs text-[#657369] dark:text-[#9AA99E] max-w-sm">{description}</p>
      {action ? (
        <div className="mt-4">{action}</div>
      ) : actionLabel && onAction ? (
        <div className="mt-4">
          <Button size="sm" onClick={onAction}>
            {actionLabel}
          </Button>
        </div>
      ) : null}
    </div>
  );
};
