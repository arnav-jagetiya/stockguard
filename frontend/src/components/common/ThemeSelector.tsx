import React from 'react';
import { Sun, Moon, Monitor } from 'lucide-react';
import { useTheme, type ThemeMode } from '../../context/ThemeContext';

export const ThemeSelector: React.FC<{ className?: string }> = ({ className = '' }) => {
  const { theme, setTheme } = useTheme();

  const options: { mode: ThemeMode; label: string; icon: React.ReactNode }[] = [
    { mode: 'light', label: 'Light', icon: <Sun className="w-3.5 h-3.5" /> },
    { mode: 'dark', label: 'Dark', icon: <Moon className="w-3.5 h-3.5" /> },
    { mode: 'system', label: 'System', icon: <Monitor className="w-3.5 h-3.5" /> },
  ];

  return (
    <div
      className={`inline-flex items-center rounded-lg p-0.5 border border-slate-200 dark:border-slate-800 bg-slate-100/80 dark:bg-slate-900/80 backdrop-blur-sm ${className}`}
      role="group"
      aria-label="Theme mode selector"
    >
      {options.map((opt) => {
        const isActive = theme === opt.mode;
        return (
          <button
            key={opt.mode}
            onClick={() => setTheme(opt.mode)}
            title={`Switch to ${opt.label} mode`}
            aria-pressed={isActive}
            className={`inline-flex items-center gap-1 px-2 py-1 text-xs font-medium rounded-md transition-all duration-150 ${
              isActive
                ? 'bg-white dark:bg-slate-800 text-cyan-600 dark:text-cyan-400 shadow-xs border border-slate-200/60 dark:border-slate-700/60'
                : 'text-slate-500 dark:text-slate-400 hover:text-slate-900 dark:hover:text-slate-200'
            }`}
          >
            {opt.icon}
            <span className="hidden sm:inline">{opt.label}</span>
          </button>
        );
      })}
    </div>
  );
};
