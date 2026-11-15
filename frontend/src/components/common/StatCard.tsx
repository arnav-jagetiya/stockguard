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
    cyan: { border: 'border-cyan-500/20', glow: 'from-cyan-500/10', text: 'text-cyan-400' },
    emerald: { border: 'border-emerald-500/20', glow: 'from-emerald-500/10', text: 'text-emerald-400' },
    amber: { border: 'border-amber-500/20', glow: 'from-amber-500/10', text: 'text-amber-400' },
    rose: { border: 'border-rose-500/20', glow: 'from-rose-500/10', text: 'text-rose-400' },
    indigo: { border: 'border-indigo-500/20', glow: 'from-indigo-500/10', text: 'text-indigo-400' },
    default: { border: 'border-slate-800', glow: 'from-slate-800/10', text: 'text-slate-300' },
  };

  const style = accentStyles[variant] || accentStyles.default;

  return (
    <div className={`relative overflow-hidden rounded-xl bg-[#0f172a] border ${style.border} p-5 transition-all duration-200 hover:shadow-lg`}>
      <div className={`absolute -right-8 -top-8 w-24 h-24 bg-gradient-to-br ${style.glow} to-transparent rounded-full blur-xl pointer-events-none`} />
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-slate-400 tracking-wide uppercase">{title}</span>
        {icon && <div className={`${style.text} p-2 rounded-lg bg-slate-900/60 border border-slate-800/80`}>{icon}</div>}
      </div>
      <div className="mt-3">
        <div className="text-2xl font-bold tracking-tight text-white font-mono">{value}</div>
        {(subtitle || trend) && (
          <div className="mt-1 flex items-center gap-2 text-xs text-slate-400">
            {trend && (
              <span className={`font-semibold ${trend.positive ? 'text-emerald-400' : 'text-rose-400'}`}>
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
