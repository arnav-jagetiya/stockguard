import React, { useState, useRef, useEffect } from 'react';
import { useAuth } from '../../context/AuthContext';
import { Building2, ChevronDown, Check, Shield } from 'lucide-react';

export const OrgSwitcher: React.FC = () => {
  const { organizations, currentOrg, switchOrganization } = useAuth();
  const [isOpen, setIsOpen] = useState(false);
  const dropdownRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target as Node)) {
        setIsOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  if (!currentOrg || organizations.length === 0) return null;

  return (
    <div className="relative" ref={dropdownRef}>
      <button
        onClick={() => setIsOpen(!isOpen)}
        className="flex items-center gap-2.5 px-3 py-1.5 rounded-lg bg-slate-100 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 hover:border-slate-300 dark:hover:border-slate-700 text-left transition-colors cursor-pointer"
        aria-expanded={isOpen}
        aria-haspopup="true"
      >
        <div className="w-6 h-6 rounded-md bg-cyan-50 dark:bg-cyan-500/10 border border-cyan-200 dark:border-cyan-500/30 flex items-center justify-center text-cyan-600 dark:text-cyan-400">
          <Building2 className="w-3.5 h-3.5" />
        </div>
        <div className="flex flex-col">
          <span className="text-xs font-semibold text-slate-800 dark:text-slate-100 max-w-[140px] truncate">
            {currentOrg.name}
          </span>
          <span className="text-[10px] text-cyan-600 dark:text-cyan-400 font-mono flex items-center gap-1">
            <Shield className="w-2.5 h-2.5" /> {currentOrg.role}
          </span>
        </div>
        <ChevronDown className={`w-3.5 h-3.5 text-slate-400 transition-transform ${isOpen ? 'rotate-180' : ''}`} />
      </button>

      {isOpen && (
        <div className="absolute left-0 mt-1.5 w-60 rounded-xl bg-white dark:bg-[#0f172a] border border-slate-200 dark:border-slate-800 shadow-xl dark:shadow-2xl p-1.5 z-50 animate-in fade-in zoom-in-95 duration-100 space-y-1">
          <div className="px-2.5 py-1.5 text-[10px] font-semibold text-slate-500 dark:text-slate-400 uppercase tracking-wider">
            Organizations
          </div>
          {organizations.map((org) => {
            const isSelected = org.id === currentOrg.id;
            return (
              <button
                key={org.id}
                onClick={async () => {
                  setIsOpen(false);
                  if (!isSelected) {
                    await switchOrganization(org.id);
                  }
                }}
                className={`w-full flex items-center justify-between px-2.5 py-2 rounded-lg text-xs transition-colors cursor-pointer text-left ${
                  isSelected
                    ? 'bg-cyan-50 dark:bg-cyan-500/10 text-cyan-700 dark:text-cyan-300 font-medium border border-cyan-200 dark:border-cyan-500/20'
                    : 'text-slate-700 dark:text-slate-300 hover:bg-slate-100 dark:hover:bg-slate-800/60 hover:text-slate-900 dark:hover:text-white'
                }`}
              >
                <div className="flex items-center gap-2 truncate">
                  <Building2 className="w-3.5 h-3.5 text-slate-400 shrink-0" />
                  <div className="truncate">
                    <p className="truncate font-medium">{org.name}</p>
                    <p className="text-[10px] text-slate-500 dark:text-slate-400 font-mono">{org.role}</p>
                  </div>
                </div>
                {isSelected && <Check className="w-3.5 h-3.5 text-cyan-600 dark:text-cyan-400 shrink-0 ml-2" />}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
};
