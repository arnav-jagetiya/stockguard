import React from 'react';
import { Link } from 'react-router-dom';
import { ShieldCheck } from 'lucide-react';

export const FooterSection: React.FC = () => {
  return (
    <footer className="border-t border-[#D9E1D7] dark:border-[#26362E] bg-[#E9EEE6] dark:bg-[#0B100F] text-[#657369] dark:text-[#9AA99E] py-16 px-6 transition-colors">
      <div className="max-w-7xl mx-auto flex flex-col md:flex-row items-start justify-between gap-10">
        <div className="space-y-4 max-w-sm">
          <div className="flex items-center gap-3">
            <div className="w-7 h-7 rounded-lg bg-gradient-to-br from-[#456B1F] to-[#147C68] dark:from-[#C6F36B] dark:to-[#6CD8C0] flex items-center justify-center text-white dark:text-[#0B100F] font-bold shadow-md shadow-[#C6F36B]/20">
              <ShieldCheck className="w-4 h-4 stroke-[2.5]" />
            </div>
            <span className="font-bold text-sm tracking-wider text-[#18231D] dark:text-[#EEF3EA]">STOCKGUARD</span>
          </div>
          <p className="text-xs text-[#657369] dark:text-[#9AA99E] leading-relaxed">
            Enterprise multi-warehouse inventory platform. Authoritative scheduled &amp; on-demand MVCC reconciliation, serialized distributed reservation locks, and immutable ledger auditability.
          </p>
          <div className="text-[11px] font-mono text-[#8A988E] dark:text-[#6C7D71]">
            PostgreSQL 16 · Spring Boot 4.1.2 · Java 21 · React 19 · Vite 8
          </div>
        </div>

        <div className="grid grid-cols-2 sm:grid-cols-3 gap-10 text-xs">
          <div className="space-y-3">
            <div className="font-semibold text-[#18231D] dark:text-[#EEF3EA] uppercase tracking-wider text-[11px] font-mono">
              Capabilities
            </div>
            <ul className="space-y-2">
              <li><a href="#capabilities" className="hover:text-[#456B1F] dark:hover:text-[#C6F36B]">Multi-Warehouse Mesh</a></li>
              <li><a href="#capabilities" className="hover:text-[#456B1F] dark:hover:text-[#C6F36B]">Inventory Ledger</a></li>
              <li><a href="#capabilities" className="hover:text-[#456B1F] dark:hover:text-[#C6F36B]">Active Reservations</a></li>
              <li><a href="#integrity" className="hover:text-[#456B1F] dark:hover:text-[#C6F36B]">Reconciliation Engine</a></li>
            </ul>
          </div>

          <div className="space-y-3">
            <div className="font-semibold text-[#18231D] dark:text-[#EEF3EA] uppercase tracking-wider text-[11px] font-mono">
              Architecture
            </div>
            <ul className="space-y-2">
              <li><a href="#architecture" className="hover:text-[#456B1F] dark:hover:text-[#C6F36B]">Modular Monolith</a></li>
              <li><a href="#architecture" className="hover:text-[#456B1F] dark:hover:text-[#C6F36B]">PostgreSQL Source of Truth</a></li>
              <li><a href="#architecture" className="hover:text-[#456B1F] dark:hover:text-[#C6F36B]">Advisory Locks (ADR-003)</a></li>
              <li><a href="#architecture" className="hover:text-[#456B1F] dark:hover:text-[#C6F36B]">Prometheus Telemetry (ADR-014)</a></li>
            </ul>
          </div>

          <div className="space-y-3">
            <div className="font-semibold text-[#18231D] dark:text-[#EEF3EA] uppercase tracking-wider text-[11px] font-mono">
              Operations
            </div>
            <ul className="space-y-2">
              <li><Link to="/login" className="hover:text-[#456B1F] dark:hover:text-[#C6F36B]">Operations Console</Link></li>
              <li><Link to="/login?demo=true" className="hover:text-[#456B1F] dark:hover:text-[#C6F36B]">Explore Live Demo</Link></li>
              <li><a href="#storytelling" className="hover:text-[#456B1F] dark:hover:text-[#C6F36B]">Storytelling Lab</a></li>
              <li><span className="text-[#456B1F] dark:text-[#C6F36B] flex items-center gap-1.5"><span className="w-1.5 h-1.5 rounded-full bg-[#456B1F] dark:bg-[#C6F36B] animate-pulse" /> All Systems Nominal</span></li>
            </ul>
          </div>
        </div>
      </div>
    </footer>
  );
};
