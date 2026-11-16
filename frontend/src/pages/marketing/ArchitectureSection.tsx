import React from 'react';
import { Card } from '../../components/common/Card';
import { Server, Database, Network } from 'lucide-react';

export const ArchitectureSection: React.FC = () => {
  return (
    <section id="architecture" className="py-24 px-6 max-w-7xl mx-auto border-t border-[#D9E1D7] dark:border-[#26362E] transition-colors">
      <div className="text-center space-y-3 mb-16">
        <span className="text-xs font-mono uppercase tracking-wider text-[#456B1F] dark:text-[#C6F36B] font-bold">
          System Architecture
        </span>
        <h2 className="text-3xl sm:text-4xl font-extrabold text-[#18231D] dark:text-[#EEF3EA] tracking-tight">
          Radical simplicity: The authoritative modular monolith
        </h2>
        <p className="text-sm text-[#657369] dark:text-[#9AA99E] max-w-xl mx-auto">
          StockGuard intentionally rejects microservice sprawl. All business domains reside in a single coherent Java 21 Spring Boot core with compile-time isolation and sub-millisecond in-process transactional boundaries.
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
        <Card className="space-y-3 hover:border-[#456B1F]/40 dark:hover:border-[#C6F36B]/40 transition-all">
          <div className="w-10 h-10 rounded-xl bg-[#E9EEE6] dark:bg-[#1B2922] border border-[#D9E1D7] dark:border-[#26362E] flex items-center justify-center text-[#456B1F] dark:text-[#C6F36B]">
            <Server className="w-5 h-5" />
          </div>
          <h3 className="text-base font-bold text-[#18231D] dark:text-[#EEF3EA] tracking-tight">Spring Boot 4.1 & Java 21</h3>
          <p className="text-xs text-[#657369] dark:text-[#9AA99E] leading-relaxed">
            Strict package encapsulation across catalog, warehouse, inventory ledger, orders, reservations, transfers, and security modules. Zero distributed transaction overhead.
          </p>
        </Card>

        <Card className="space-y-3 hover:border-[#456B1F]/40 dark:hover:border-[#C6F36B]/40 transition-all">
          <div className="w-10 h-10 rounded-xl bg-[#E9EEE6] dark:bg-[#1B2922] border border-[#D9E1D7] dark:border-[#26362E] flex items-center justify-center text-[#147C68] dark:text-[#6CD8C0]">
            <Database className="w-5 h-5" />
          </div>
          <h3 className="text-base font-bold text-[#18231D] dark:text-[#EEF3EA] tracking-tight">PostgreSQL 16 Source of Truth</h3>
          <p className="text-xs text-[#657369] dark:text-[#9AA99E] leading-relaxed">
            Flyway-versioned relational schema with composite foreign keys, check constraints, partial unique active indexes, and MATCH SIMPLE organization scoping.
          </p>
        </Card>

        <Card className="space-y-3 hover:border-[#456B1F]/40 dark:hover:border-[#C6F36B]/40 transition-all">
          <div className="w-10 h-10 rounded-xl bg-[#E9EEE6] dark:bg-[#1B2922] border border-[#D9E1D7] dark:border-[#26362E] flex items-center justify-center text-[#456B1F] dark:text-[#C6F36B]">
            <Network className="w-5 h-5" />
          </div>
          <h3 className="text-base font-bold text-[#18231D] dark:text-[#EEF3EA] tracking-tight">3-Network Production Topology</h3>
          <p className="text-xs text-[#657369] dark:text-[#9AA99E] leading-relaxed">
            Strict Docker network isolation separates public ingress, database communication, and Prometheus scrape traffic. Prometheus has zero network route to PostgreSQL.
          </p>
        </Card>
      </div>
    </section>
  );
};
