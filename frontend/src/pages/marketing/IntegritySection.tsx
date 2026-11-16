import React from 'react';
import { Check } from 'lucide-react';

export const IntegritySection: React.FC = () => {
  return (
    <section id="integrity" className="py-24 px-6 max-w-7xl mx-auto border-t border-slate-200 dark:border-slate-900 transition-colors">
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-12 items-center">
        <div className="space-y-6">
          <div className="space-y-2">
            <span className="text-xs font-mono uppercase tracking-wider text-cyan-600 dark:text-cyan-400 font-semibold">
              Zero-Trust Reconciliation
            </span>
            <h2 className="text-3xl sm:text-4xl font-extrabold text-slate-900 dark:text-white tracking-tight">
              Why traditional WMS systems silently lose stock
            </h2>
          </div>

          <p className="text-sm text-slate-600 dark:text-slate-400 leading-relaxed">
            Standard warehouse management systems rely on separate caching layers (Redis) and asynchronous messaging queues (Kafka). Under high concurrent load or transient network faults, message drops and race conditions produce phantom inventory that operators only discover weeks later during physical counts.
          </p>

          <div className="space-y-4">
            <div className="flex items-start gap-3">
              <div className="w-6 h-6 rounded-md bg-cyan-50 dark:bg-cyan-500/10 border border-cyan-300 dark:border-cyan-500/30 flex items-center justify-center text-cyan-600 dark:text-cyan-400 shrink-0 mt-0.5">
                <Check className="w-3.5 h-3.5" />
              </div>
              <div>
                <h4 className="text-sm font-semibold text-slate-900 dark:text-slate-200">
                  PostgreSQL Serializable & Repeatable Read
                </h4>
                <p className="text-xs text-slate-600 dark:text-slate-400">
                  Transactions execute under strict snapshot isolation. Stale reads cannot mutate balances, and concurrent updates are resolved deterministically.
                </p>
              </div>
            </div>

            <div className="flex items-start gap-3">
              <div className="w-6 h-6 rounded-md bg-indigo-50 dark:bg-indigo-500/10 border border-indigo-300 dark:border-indigo-500/30 flex items-center justify-center text-indigo-600 dark:text-indigo-400 shrink-0 mt-0.5">
                <Check className="w-3.5 h-3.5" />
              </div>
              <div>
                <h4 className="text-sm font-semibold text-slate-900 dark:text-slate-200">
                  PostgreSQL Transaction-Scoped Advisory Locks
                </h4>
                <p className="text-xs text-slate-600 dark:text-slate-400">
                  Coordination between multiple application nodes uses native PostgreSQL advisory locks (<code className="text-cyan-600 dark:text-cyan-400">pg_try_advisory_xact_lock</code>) with microsecond latency and zero lock contention leaks.
                </p>
              </div>
            </div>

            <div className="flex items-start gap-3">
              <div className="w-6 h-6 rounded-md bg-emerald-50 dark:bg-emerald-500/10 border border-emerald-300 dark:border-emerald-500/30 flex items-center justify-center text-emerald-600 dark:text-emerald-400 shrink-0 mt-0.5">
                <Check className="w-3.5 h-3.5" />
              </div>
              <div>
                <h4 className="text-sm font-semibold text-slate-900 dark:text-slate-200">
                  Optimistic Concurrency Control (OCC)
                </h4>
                <p className="text-xs text-slate-600 dark:text-slate-400">
                  Every discrepancy record tracks an integer <code className="text-cyan-600 dark:text-cyan-400">version</code> field. Concurrent operator triage and automated background sweeps can never overwrite each other.
                </p>
              </div>
            </div>
          </div>
        </div>

        {/* Comparison Table Card */}
        <div className="rounded-2xl bg-white dark:bg-[#0b101e] border border-slate-200 dark:border-slate-800 p-6 space-y-4 shadow-xl">
          <div className="flex items-center justify-between pb-3 border-b border-slate-200 dark:border-slate-800 text-xs font-mono">
            <span className="text-slate-500 dark:text-slate-400 uppercase">Architecture Invariant</span>
            <span className="text-cyan-600 dark:text-cyan-400 font-semibold">STOCKGUARD</span>
          </div>

          <div className="space-y-3 font-mono text-xs">
            <div className="flex items-center justify-between py-2 border-b border-slate-100 dark:border-slate-800/60">
              <span className="text-slate-600 dark:text-slate-400">Source of Truth</span>
              <span className="text-emerald-600 dark:text-emerald-400 font-semibold">PostgreSQL 16 MVCC</span>
            </div>
            <div className="flex items-center justify-between py-2 border-b border-slate-100 dark:border-slate-800/60">
              <span className="text-slate-600 dark:text-slate-400">External Cache Sprawl</span>
              <span className="text-emerald-600 dark:text-emerald-400 font-semibold">Zero (Eliminated)</span>
            </div>
            <div className="flex items-center justify-between py-2 border-b border-slate-100 dark:border-slate-800/60">
              <span className="text-slate-600 dark:text-slate-400">Balance Integrity</span>
              <span className="text-emerald-600 dark:text-emerald-400 font-semibold">100% Verifiable via Ledger</span>
            </div>
            <div className="flex items-center justify-between py-2 border-b border-slate-100 dark:border-slate-800/60">
              <span className="text-slate-600 dark:text-slate-400">Reconciliation Frequency</span>
              <span className="text-emerald-600 dark:text-emerald-400 font-semibold">Continuous Automated Sweeps</span>
            </div>
            <div className="flex items-center justify-between py-2 border-b border-slate-100 dark:border-slate-800/60">
              <span className="text-slate-600 dark:text-slate-400">Tenant Isolation</span>
              <span className="text-emerald-600 dark:text-emerald-400 font-semibold">Database Schema FK & Keys</span>
            </div>
            <div className="flex items-center justify-between py-2">
              <span className="text-slate-600 dark:text-slate-400">Telemetry Export</span>
              <span className="text-emerald-600 dark:text-emerald-400 font-semibold">14 Standardized Prometheus Gauges</span>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
};
