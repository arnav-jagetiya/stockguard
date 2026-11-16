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
        className="flex items-center gap-2.5 px-3 py-1.5 rounded-lg bg-[#E9EEE6] dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] hover:border-[#657369]/40 dark:hover:border-[#9AA99E]/40 text-left transition-colors cursor-pointer"
        aria-expanded={isOpen}
        aria-haspopup="true"
      >
        <div className="w-6 h-6 rounded-md bg-white dark:bg-[#1B2922] border border-[#D9E1D7] dark:border-[#26362E] flex items-center justify-center text-[#456B1F] dark:text-[#C6F36B]">
          <Building2 className="w-3.5 h-3.5" />
        </div>
        <div className="flex flex-col">
          <span className="text-xs font-semibold text-[#18231D] dark:text-[#EEF3EA] max-w-[140px] truncate">
            {currentOrg.name}
          </span>
          <span className="text-[10px] text-[#456B1F] dark:text-[#C6F36B] font-mono flex items-center gap-1">
            <Shield className="w-2.5 h-2.5" /> {currentOrg.role}
          </span>
        </div>
        <ChevronDown className={`w-3.5 h-3.5 text-[#8A988E] dark:text-[#6C7D71] transition-transform ${isOpen ? 'rotate-180' : ''}`} />
      </button>

      {isOpen && (
        <div className="absolute left-0 mt-1.5 w-60 rounded-xl bg-white dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] shadow-xl dark:shadow-2xl p-1.5 z-50 animate-in fade-in zoom-in-95 duration-100 space-y-1">
          <div className="px-2.5 py-1.5 text-[10px] font-semibold text-[#8A988E] dark:text-[#6C7D71] uppercase tracking-wider font-mono">
            Tenant Organizations
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
                    ? 'bg-[#E9EEE6] dark:bg-[#C6F36B]/15 text-[#456B1F] dark:text-[#C6F36B] font-semibold'
                    : 'text-[#18231D] dark:text-[#EEF3EA] hover:bg-[#F9FAF7] dark:hover:bg-[#182621]'
                }`}
              >
                <div className="flex flex-col">
                  <span className="font-medium truncate max-w-[160px]">{org.name}</span>
                  <span className="text-[10px] text-[#8A988E] dark:text-[#6C7D71] font-mono">{org.role}</span>
                </div>
                {isSelected && <Check className="w-3.5 h-3.5 text-[#456B1F] dark:text-[#C6F36B]" />}
              </button>
            );
          })}
        </div>
      )}
    </div>
  );
};
