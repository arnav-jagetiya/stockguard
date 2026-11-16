import React from 'react';
import { Card } from '../../components/common/Card';
import { Server, Database, Network } from 'lucide-react';

export const ArchitectureSection: React.FC = () => {
  return (
    <section id="architecture" className="py-24 px-6 max-w-7xl mx-auto border-t border-slate-200 dark:border-slate-900 transition-colors">
      <div className="text-center space-y-3 mb-16">
        <span className="text-xs font-mono uppercase tracking-wider text-cyan-600 dark:text-cyan-400 font-semibold">
          System Architecture
        </span>
        <h2 className="text-3xl sm:text-4xl font-extrabold text-slate-900 dark:text-white tracking-tight">
          Radical simplicity: The authoritative modular monolith
        </h2>
        <p className="text-sm text-slate-600 dark:text-slate-400 max-w-xl mx-auto">
          StockGuard intentionally rejects microservice sprawl. All business domains reside in a single coherent Java 21 Spring Boot core with compile-time isolation and sub-millisecond in-process transactional boundaries.
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <Card className="space-y-3">
          <div className="w-10 h-10 rounded-xl bg-slate-100 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 flex items-center justify-center text-cyan-600 dark:text-cyan-400">
            <Server className="w-5 h-5" />
          </div>
          <h3 className="text-base font-bold text-slate-900 dark:text-white tracking-tight">Spring Boot 4.1 & Java 21</h3>
          <p className="text-xs text-slate-600 dark:text-slate-400 leading-relaxed">
            Strict package encapsulation across catalog, warehouse, inventory ledger, orders, reservations, transfers, and security modules. Zero distributed transaction overhead.
          </p>
        </Card>

        <Card className="space-y-3">
          <div className="w-10 h-10 rounded-xl bg-slate-100 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 flex items-center justify-center text-indigo-600 dark:text-indigo-400">
            <Database className="w-5 h-5" />
          </div>
          <h3 className="text-base font-bold text-slate-900 dark:text-white tracking-tight">PostgreSQL 16 Source of Truth</h3>
          <p className="text-xs text-slate-600 dark:text-slate-400 leading-relaxed">
            Flyway-versioned relational schema with composite foreign keys, check constraints, partial unique active indexes, and MATCH SIMPLE organization scoping.
          </p>
        </Card>

        <Card className="space-y-3">
          <div className="w-10 h-10 rounded-xl bg-slate-100 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 flex items-center justify-center text-emerald-600 dark:text-emerald-400">
            <Network className="w-5 h-5" />
          </div>
          <h3 className="text-base font-bold text-slate-900 dark:text-white tracking-tight">3-Network Production Topology</h3>
          <p className="text-xs text-slate-600 dark:text-slate-400 leading-relaxed">
            Strict Docker network isolation separates public ingress, database communication, and Prometheus scrape traffic. Prometheus has zero network route to PostgreSQL.
          </p>
        </Card>
      </div>
    </section>
  );
};
