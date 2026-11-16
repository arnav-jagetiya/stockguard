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
      label: 'Warm Mineral (Light)',
      description: 'Porcelain & limestone with Signal Green clarity',
      icon: <Sun className="w-4 h-4 text-[#456B1F]" />,
      iconBg: 'bg-[#E9EEE6] text-[#456B1F]',
    },
    {
      mode: 'dark',
      label: 'Midnight Mineral (Dark)',
      description: 'Carbon & deep pine with Signal Lime precision',
      icon: <Moon className="w-4 h-4 text-[#C6F36B]" />,
      iconBg: 'bg-[#1B2922] text-[#C6F36B]',
    },
    {
      mode: 'system',
      label: 'System Preference',
      description: 'Synchronizes dynamically with operating system',
      icon: <Monitor className="w-4 h-4 text-[#147C68] dark:text-[#6CD8C0]" />,
      iconBg: 'bg-[#DEE5DA] dark:bg-[#22332B] text-[#147C68] dark:text-[#6CD8C0]',
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
    ? (resolvedTheme === 'dark' ? <Moon className="w-3.5 h-3.5 text-[#C6F36B]" /> : <Sun className="w-3.5 h-3.5 text-[#456B1F]" />)
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
        className="inline-flex items-center gap-2 px-2.5 py-1.5 rounded-lg text-xs font-medium text-[#18231D] dark:text-[#EEF3EA] bg-white dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] hover:bg-[#F9FAF7] dark:hover:bg-[#182621] shadow-xs focus:outline-none focus:ring-2 focus:ring-[#456B1F]/30 dark:focus:ring-[#C6F36B]/30 transition-all cursor-pointer"
      >
        <span className="flex items-center justify-center w-5 h-5 rounded-md bg-[#E9EEE6] dark:bg-[#1B2922]">
          {activeIcon}
        </span>
        <span className="hidden sm:inline font-medium capitalize">{theme}</span>
        <ChevronDown
          className={`w-3.5 h-3.5 text-[#8A988E] dark:text-[#6C7D71] transition-transform duration-200 ${
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
          className="absolute right-0 mt-2 w-80 rounded-xl bg-white/95 dark:bg-[#111A17]/95 backdrop-blur-md border border-[#D9E1D7] dark:border-[#26362E] shadow-2xl p-2 z-50 focus:outline-none animate-in fade-in zoom-in-95 duration-150 origin-top-right text-[#18231D] dark:text-[#EEF3EA]"
        >
          {/* Header */}
          <div className="px-3 py-2 border-b border-[#D9E1D7] dark:border-[#26362E] mb-1.5">
            <p className="text-[11px] font-semibold tracking-wider text-[#657369] dark:text-[#9AA99E] uppercase font-mono">
              Mineral & Signal Appearance
            </p>
            <p className="text-xs text-[#8A988E] dark:text-[#6C7D71] mt-0.5">
              Select calibrated aesthetic or synchronize with OS
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
                      ? 'bg-[#E9EEE6] dark:bg-[#C6F36B]/15 border border-[#456B1F]/30 dark:border-[#C6F36B]/30'
                      : 'hover:bg-[#F9FAF7] dark:hover:bg-[#182621] border border-transparent'
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
                            ? 'text-[#456B1F] dark:text-[#C6F36B]'
                            : 'text-[#18231D] dark:text-[#EEF3EA]'
                        }`}
                      >
                        {option.label}
                      </span>
                      {isSelected && (
                        <Check className="w-4 h-4 text-[#456B1F] dark:text-[#C6F36B] shrink-0" />
                      )}
                    </div>
                    <p className="text-[11px] text-[#657369] dark:text-[#9AA99E] mt-0.5 leading-snug">
                      {option.description}
                    </p>
                  </div>
                </button>
              );
            })}
          </div>

          {/* Active status footer */}
          <div className="mt-2 pt-2 px-2.5 pb-1 border-t border-[#D9E1D7] dark:border-[#26362E] flex items-center justify-between text-[11px] text-[#8A988E] dark:text-[#6C7D71]">
            <span>Active palette:</span>
            <span className="font-mono text-[#456B1F] dark:text-[#C6F36B] font-semibold capitalize">
              {resolvedTheme === 'dark' ? 'Midnight Mineral' : 'Warm Mineral'}
            </span>
          </div>
        </div>
      )}
    </div>
  );
};
