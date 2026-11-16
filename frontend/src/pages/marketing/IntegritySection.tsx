import React from 'react';
import { Check } from 'lucide-react';

export const IntegritySection: React.FC = () => {
  return (
    <section id="integrity" className="py-24 px-6 max-w-7xl mx-auto border-t border-[#D9E1D7] dark:border-[#26362E] transition-colors">
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-12 items-center">
        <div className="space-y-6">
          <div className="space-y-2">
            <span className="text-xs font-mono uppercase tracking-wider text-[#456B1F] dark:text-[#C6F36B] font-bold">
              Zero-Trust Reconciliation
            </span>
            <h2 className="text-3xl sm:text-4xl font-extrabold text-[#18231D] dark:text-[#EEF3EA] tracking-tight">
              Why traditional WMS systems silently lose stock
            </h2>
          </div>

          <p className="text-sm text-[#657369] dark:text-[#9AA99E] leading-relaxed">
            Standard warehouse management systems rely on separate caching layers (Redis) and asynchronous messaging queues (Kafka). Under high concurrent load or transient network faults, message drops and race conditions produce phantom inventory that operators only discover weeks later during physical counts.
          </p>

          <div className="space-y-4">
            <div className="flex items-start gap-3">
              <div className="w-6 h-6 rounded-md bg-[#E9EEE6] dark:bg-[#C6F36B]/15 border border-[#D9E1D7] dark:border-[#C6F36B]/30 flex items-center justify-center text-[#456B1F] dark:text-[#C6F36B] shrink-0 mt-0.5">
                <Check className="w-3.5 h-3.5" />
              </div>
              <div>
                <h4 className="text-sm font-semibold text-[#18231D] dark:text-[#EEF3EA]">
                  PostgreSQL Serializable & Repeatable Read
                </h4>
                <p className="text-xs text-[#657369] dark:text-[#9AA99E]">
                  Transactions execute under strict snapshot isolation. Stale reads cannot mutate balances, and concurrent updates are resolved deterministically.
                </p>
              </div>
            </div>

            <div className="flex items-start gap-3">
              <div className="w-6 h-6 rounded-md bg-[#E9EEE6] dark:bg-[#6CD8C0]/15 border border-[#D9E1D7] dark:border-[#6CD8C0]/30 flex items-center justify-center text-[#147C68] dark:text-[#6CD8C0] shrink-0 mt-0.5">
                <Check className="w-3.5 h-3.5" />
              </div>
              <div>
                <h4 className="text-sm font-semibold text-[#18231D] dark:text-[#EEF3EA]">
                  PostgreSQL Transaction-Scoped Advisory Locks
                </h4>
                <p className="text-xs text-[#657369] dark:text-[#9AA99E]">
                  Coordination between multiple application nodes uses native PostgreSQL advisory locks (<code className="text-[#456B1F] dark:text-[#C6F36B]">pg_try_advisory_xact_lock</code>) with microsecond latency and zero lock contention leaks.
                </p>
              </div>
            </div>

            <div className="flex items-start gap-3">
              <div className="w-6 h-6 rounded-md bg-[#E9EEE6] dark:bg-[#C6F36B]/15 border border-[#D9E1D7] dark:border-[#C6F36B]/30 flex items-center justify-center text-[#456B1F] dark:text-[#C6F36B] shrink-0 mt-0.5">
                <Check className="w-3.5 h-3.5" />
              </div>
              <div>
                <h4 className="text-sm font-semibold text-[#18231D] dark:text-[#EEF3EA]">
                  Optimistic Concurrency Control (OCC)
                </h4>
                <p className="text-xs text-[#657369] dark:text-[#9AA99E]">
                  Every discrepancy record tracks an integer <code className="text-[#456B1F] dark:text-[#C6F36B]">version</code> field. Concurrent operator triage and automated background sweeps can never overwrite each other.
                </p>
              </div>
            </div>
          </div>
        </div>

        {/* Comparison Table Card */}
        <div className="rounded-2xl bg-white dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] p-6 space-y-4 shadow-xl">
          <div className="flex items-center justify-between pb-3 border-b border-[#D9E1D7] dark:border-[#26362E] text-xs font-mono">
            <span className="text-[#8A988E] dark:text-[#6C7D71] uppercase font-semibold">Architecture Invariant</span>
            <span className="text-[#456B1F] dark:text-[#C6F36B] font-bold">STOCKGUARD LIVING WAREHOUSE</span>
          </div>

          <div className="space-y-3 font-mono text-xs">
            <div className="flex items-center justify-between py-2 border-b border-[#D9E1D7]/60 dark:border-[#26362E]/60">
              <span className="text-[#657369] dark:text-[#9AA99E]">Source of Truth</span>
              <span className="text-[#456B1F] dark:text-[#C6F36B] font-semibold">PostgreSQL 16 MVCC</span>
            </div>
            <div className="flex items-center justify-between py-2 border-b border-[#D9E1D7]/60 dark:border-[#26362E]/60">
              <span className="text-[#657369] dark:text-[#9AA99E]">External Cache Sprawl</span>
              <span className="text-[#456B1F] dark:text-[#C6F36B] font-semibold">Zero (Eliminated)</span>
            </div>
            <div className="flex items-center justify-between py-2 border-b border-[#D9E1D7]/60 dark:border-[#26362E]/60">
              <span className="text-[#657369] dark:text-[#9AA99E]">Balance Integrity</span>
              <span className="text-[#456B1F] dark:text-[#C6F36B] font-semibold">100% Verifiable via Ledger</span>
            </div>
            <div className="flex items-center justify-between py-2 border-b border-[#D9E1D7]/60 dark:border-[#26362E]/60">
              <span className="text-[#657369] dark:text-[#9AA99E]">Reconciliation Frequency</span>
              <span className="text-[#456B1F] dark:text-[#C6F36B] font-semibold">Continuous Automated Sweeps</span>
            </div>
            <div className="flex items-center justify-between py-2 border-b border-[#D9E1D7]/60 dark:border-[#26362E]/60">
              <span className="text-[#657369] dark:text-[#9AA99E]">Tenant Isolation</span>
              <span className="text-[#456B1F] dark:text-[#C6F36B] font-semibold">Database Schema FK & Keys</span>
            </div>
            <div className="flex items-center justify-between py-2">
              <span className="text-[#657369] dark:text-[#9AA99E]">Telemetry Export</span>
              <span className="text-[#456B1F] dark:text-[#C6F36B] font-semibold">14 Standardized Prometheus Gauges</span>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
};
