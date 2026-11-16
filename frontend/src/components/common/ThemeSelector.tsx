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
      className={`inline-flex items-center rounded-lg p-0.5 border border-[#D9E1D7] dark:border-[#26362E] bg-[#E9EEE6] dark:bg-[#16221E] backdrop-blur-sm ${className}`}
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
                ? 'bg-white dark:bg-[#111A17] text-[#456B1F] dark:text-[#C6F36B] shadow-xs border border-[#D9E1D7] dark:border-[#26362E]'
                : 'text-[#657369] dark:text-[#9AA99E] hover:text-[#18231D] dark:hover:text-[#EEF3EA]'
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
