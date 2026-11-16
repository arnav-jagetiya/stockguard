import React from 'react';

interface StatCardProps {
  title: string;
  value: string | number;
  subtitle?: string;
  icon?: React.ReactNode;
  trend?: {
    value: string;
    positive?: boolean;
  };
  variant?: 'cyan' | 'emerald' | 'amber' | 'rose' | 'indigo' | 'default';
}

export const StatCard: React.FC<StatCardProps> = ({
  title,
  value,
  subtitle,
  icon,
  trend,
  variant = 'default',
}) => {
  const accentStyles: Record<string, { border: string; glow: string; text: string }> = {
    cyan: { border: 'border-teal-500/30 dark:border-[#6CD8C0]/30', glow: 'from-teal-500/10', text: 'text-[#147C68] dark:text-[#6CD8C0]' },
    emerald: { border: 'border-[#456B1F]/30 dark:border-[#C6F36B]/30', glow: 'from-[#456B1F]/10', text: 'text-[#456B1F] dark:text-[#C6F36B]' },
    amber: { border: 'border-amber-500/30 dark:border-amber-500/20', glow: 'from-amber-500/10', text: 'text-amber-600 dark:text-amber-400' },
    rose: { border: 'border-rose-500/30 dark:border-rose-500/20', glow: 'from-rose-500/10', text: 'text-rose-600 dark:text-rose-400' },
    indigo: { border: 'border-indigo-500/30 dark:border-indigo-500/20', glow: 'from-indigo-500/10', text: 'text-indigo-600 dark:text-indigo-400' },
    default: { border: 'border-[#D9E1D7] dark:border-[#26362E]', glow: 'from-slate-500/5', text: 'text-[#657369] dark:text-[#9AA99E]' },
  };

  const style = accentStyles[variant] || accentStyles.default;

  return (
    <div className={`relative overflow-hidden rounded-xl bg-white dark:bg-[#111A17] border ${style.border} p-5 transition-all duration-200 shadow-xs hover:shadow-md dark:shadow-none`}>
      <div className={`absolute -right-8 -top-8 w-24 h-24 bg-gradient-to-br ${style.glow} to-transparent rounded-full blur-xl pointer-events-none`} />
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-[#657369] dark:text-[#9AA99E] tracking-wide uppercase">{title}</span>
        {icon && <div className={`${style.text} p-2 rounded-lg bg-[#E9EEE6] dark:bg-[#16221E] border border-[#D9E1D7] dark:border-[#26362E]`}>{icon}</div>}
      </div>
      <div className="mt-3">
        <div className="text-2xl font-bold tracking-tight text-[#18231D] dark:text-[#EEF3EA] font-mono">{value}</div>
        {(subtitle || trend) && (
          <div className="mt-1 flex items-center gap-2 text-xs text-[#657369] dark:text-[#9AA99E]">
            {trend && (
              <span className={`font-semibold ${trend.positive ? 'text-[#456B1F] dark:text-[#C6F36B]' : 'text-rose-600 dark:text-rose-400'}`}>
                {trend.value}
              </span>
            )}
            {subtitle && <span>{subtitle}</span>}
          </div>
        )}
      </div>
    </div>
  );
};
