import React from 'react';
import { Link } from 'react-router-dom';
import { ShieldCheck } from 'lucide-react';

export const FooterSection: React.FC = () => {
  return (
    <footer className="border-t border-slate-200 dark:border-slate-900 bg-slate-100 dark:bg-[#060910] text-slate-600 dark:text-slate-400 py-16 px-6 transition-colors">
      <div className="max-w-7xl mx-auto flex flex-col md:flex-row items-start justify-between gap-10">
        <div className="space-y-4 max-w-sm">
          <div className="flex items-center gap-3">
            <div className="w-7 h-7 rounded-lg bg-gradient-to-br from-cyan-500 to-indigo-600 flex items-center justify-center text-slate-950 font-bold shadow-md shadow-cyan-500/20">
              <ShieldCheck className="w-4 h-4 text-slate-950 stroke-[2.5]" />
            </div>
            <span className="font-bold text-sm tracking-wider text-slate-900 dark:text-slate-100">STOCKGUARD</span>
          </div>
          <p className="text-xs text-slate-500 leading-relaxed">
            Enterprise multi-warehouse inventory platform. Authoritative, continuous MVCC reconciliation, serialized distributed reservation locks, and immutable ledger auditability.
          </p>
          <div className="text-[11px] font-mono text-slate-500">
            PostgreSQL 16 · Spring Boot 4.1.2 · Java 21 · React 19 · Vite 8
          </div>
        </div>

        <div className="grid grid-cols-2 sm:grid-cols-3 gap-10 text-xs">
          <div className="space-y-3">
            <div className="font-semibold text-slate-900 dark:text-slate-200 uppercase tracking-wider text-[11px]">
              Capabilities
            </div>
            <ul className="space-y-2">
              <li><a href="#capabilities" className="hover:text-cyan-600 dark:hover:text-cyan-400">Multi-Warehouse Routing</a></li>
              <li><a href="#capabilities" className="hover:text-cyan-600 dark:hover:text-cyan-400">Inventory Ledger</a></li>
              <li><a href="#capabilities" className="hover:text-cyan-600 dark:hover:text-cyan-400">Active Reservations</a></li>
              <li><a href="#integrity" className="hover:text-cyan-600 dark:hover:text-cyan-400">Reconciliation Engine</a></li>
            </ul>
          </div>

          <div className="space-y-3">
            <div className="font-semibold text-slate-900 dark:text-slate-200 uppercase tracking-wider text-[11px]">
              Architecture
            </div>
            <ul className="space-y-2">
              <li><a href="#architecture" className="hover:text-cyan-600 dark:hover:text-cyan-400">Modular Monolith</a></li>
              <li><a href="#architecture" className="hover:text-cyan-600 dark:hover:text-cyan-400">PostgreSQL Source of Truth</a></li>
              <li><a href="#architecture" className="hover:text-cyan-600 dark:hover:text-cyan-400">Advisory Locks (ADR-003)</a></li>
              <li><a href="#architecture" className="hover:text-cyan-600 dark:hover:text-cyan-400">Prometheus Telemetry (ADR-014)</a></li>
            </ul>
          </div>

          <div className="space-y-3">
            <div className="font-semibold text-slate-900 dark:text-slate-200 uppercase tracking-wider text-[11px]">
              Operations
            </div>
            <ul className="space-y-2">
              <li><Link to="/login" className="hover:text-cyan-600 dark:hover:text-cyan-400">Operations Console</Link></li>
              <li><Link to="/register" className="hover:text-cyan-600 dark:hover:text-cyan-400">Create Account</Link></li>
              <li><a href="#demo" className="hover:text-cyan-600 dark:hover:text-cyan-400">Interactive Simulation Lab</a></li>
              <li><span className="text-emerald-600 dark:text-emerald-400 flex items-center gap-1.5"><span className="w-1.5 h-1.5 rounded-full bg-emerald-500 animate-pulse" /> All Systems Nominal</span></li>
            </ul>
          </div>
        </div>
      </div>

      <div className="max-w-7xl mx-auto mt-12 pt-8 border-t border-slate-200 dark:border-slate-900/80 flex flex-col sm:flex-row items-center justify-between text-[11px] text-slate-500 dark:text-slate-600 gap-4">
        <div>© 2026 StockGuard Platform. All rights reserved. Zero-drift enterprise inventory.</div>
        <div className="flex items-center gap-6">
          <span>Enterprise License</span>
          <span>Security & Compliance</span>
          <span>Terms of Service</span>
        </div>
      </div>
    </footer>
  );
};
