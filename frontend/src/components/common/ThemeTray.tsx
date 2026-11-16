import React, { useState, useRef, useEffect } from 'react';
import { Sun, Moon, Monitor, Check, ChevronDown } from 'lucide-react';
import { useTheme, type ThemeMode } from '../../context/ThemeContext';

interface ThemeOption {
  mode: ThemeMode;
  label: string;
  description: string;
  icon: React.ReactNode;
  iconBg: string;
}

export const ThemeTray: React.FC<{ className?: string }> = ({ className = '' }) => {
  const { theme, resolvedTheme, setTheme } = useTheme();
  const [isOpen, setIsOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  const options: ThemeOption[] = [
    {
      mode: 'light',
      label: 'Light',
      description: 'Clean, high-contrast daytime interface',
      icon: <Sun className="w-4 h-4 text-amber-500" />,
      iconBg: 'bg-amber-500/10 dark:bg-amber-500/20 text-amber-600 dark:text-amber-400',
    },
    {
      mode: 'dark',
      label: 'Dark',
      description: 'Deep midnight navy low-strain palette',
      icon: <Moon className="w-4 h-4 text-cyan-400" />,
      iconBg: 'bg-cyan-500/10 dark:bg-cyan-500/20 text-cyan-600 dark:text-cyan-400',
    },
    {
      mode: 'system',
      label: 'System Preference',
      description: 'Automatically synchronizes with your device OS',
      icon: <Monitor className="w-4 h-4 text-indigo-500" />,
      iconBg: 'bg-indigo-500/10 dark:bg-indigo-500/20 text-indigo-600 dark:text-indigo-400',
    },
  ];

  // Close on outside click
  useEffect(() => {
    const handleOutsideClick = (event: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setIsOpen(false);
      }
    };
    if (isOpen) {
      document.addEventListener('mousedown', handleOutsideClick);
    }
    return () => document.removeEventListener('mousedown', handleOutsideClick);
  }, [isOpen]);

  // Close on Escape key
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && isOpen) {
        setIsOpen(false);
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [isOpen]);

  const currentOption = options.find((opt) => opt.mode === theme) || options[2];
  const activeIcon = theme === 'system'
    ? (resolvedTheme === 'dark' ? <Moon className="w-3.5 h-3.5 text-cyan-500" /> : <Sun className="w-3.5 h-3.5 text-amber-500" />)
    : currentOption.icon;

  return (
    <div className={`relative inline-block text-left ${className}`} ref={containerRef}>
      {/* Trigger Button */}
      <button
        type="button"
        data-testid="theme-tray-trigger"
        onClick={() => setIsOpen((prev) => !prev)}
        aria-haspopup="true"
        aria-expanded={isOpen}
        aria-label={`Theme mode: currently ${theme}. Click to change.`}
        className="inline-flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-xs font-medium text-slate-700 dark:text-slate-200 bg-white dark:bg-slate-900 border border-slate-200/90 dark:border-slate-800/90 hover:bg-slate-50 dark:hover:bg-slate-800/60 shadow-xs focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all cursor-pointer"
      >
        <span className="flex items-center justify-center w-5 h-5 rounded-md bg-slate-100 dark:bg-slate-800">
          {activeIcon}
        </span>
        <span className="hidden sm:inline font-medium capitalize">{theme}</span>
        <ChevronDown
          className={`w-3.5 h-3.5 text-slate-400 transition-transform duration-200 ${
            isOpen ? 'rotate-180' : ''
          }`}
        />
      </button>

      {/* Popover Menu Tray */}
      {isOpen && (
        <div
          role="menu"
          aria-orientation="vertical"
          aria-label="Appearance options"
          className="absolute right-0 mt-2 w-72 rounded-xl bg-white/95 dark:bg-[#0f172a]/95 backdrop-blur-md border border-slate-200 dark:border-slate-800 shadow-xl shadow-slate-900/10 dark:shadow-black/40 p-2 z-50 focus:outline-none animate-in fade-in zoom-in-95 duration-150 origin-top-right"
        >
          {/* Header */}
          <div className="px-3 py-2 border-b border-slate-100 dark:border-slate-800/80 mb-1.5">
            <p className="text-[11px] font-semibold tracking-wider text-slate-400 dark:text-slate-500 uppercase">
              Interface Appearance
            </p>
            <p className="text-xs text-slate-500 dark:text-slate-400 mt-0.5">
              Select theme or synchronize with operating system
            </p>
          </div>

          {/* Options */}
          <div className="space-y-1">
            {options.map((option) => {
              const isSelected = theme === option.mode;
              return (
                <button
                  key={option.mode}
                  type="button"
                  role="menuitem"
                  data-theme-mode={option.mode}
                  onClick={() => {
                    setTheme(option.mode);
                    setIsOpen(false);
                  }}
                  className={`w-full flex items-start gap-3 p-2.5 rounded-lg text-left transition-all cursor-pointer ${
                    isSelected
                      ? 'bg-cyan-500/10 dark:bg-cyan-500/15 border border-cyan-500/30'
                      : 'hover:bg-slate-100/80 dark:hover:bg-slate-800/60 border border-transparent'
                  }`}
                >
                  <div
                    className={`flex items-center justify-center w-8 h-8 rounded-lg shrink-0 ${option.iconBg}`}
                  >
                    {option.icon}
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between">
                      <span
                        className={`text-xs font-semibold ${
                          isSelected
                            ? 'text-cyan-700 dark:text-cyan-300'
                            : 'text-slate-800 dark:text-slate-200'
                        }`}
                      >
                        {option.label}
                      </span>
                      {isSelected && (
                        <Check className="w-4 h-4 text-cyan-600 dark:text-cyan-400 shrink-0" />
                      )}
                    </div>
                    <p className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5 leading-snug">
                      {option.description}
                    </p>
                  </div>
                </button>
              );
            })}
          </div>

          {/* Active status footer */}
          <div className="mt-2 pt-2 px-2.5 pb-1 border-t border-slate-100 dark:border-slate-800/80 flex items-center justify-between text-[11px] text-slate-400 dark:text-slate-500">
            <span>Currently active:</span>
            <span className="font-mono text-cyan-600 dark:text-cyan-400 font-medium capitalize">
              {resolvedTheme} palette
            </span>
          </div>
        </div>
      )}
    </div>
  );
};
