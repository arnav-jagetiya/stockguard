import React from 'react';
import { Card } from '../../components/common/Card';
import {
  Boxes,
  RotateCw,
  GitCommit,
  Split,
  Timer,
  Fingerprint,
} from 'lucide-react';

export const CapabilitiesSection: React.FC = () => {
  const features = [
    {
      icon: <Boxes className="w-5 h-5 text-[#456B1F] dark:text-[#C6F36B]" />,
      title: 'Multi-Warehouse Mesh Allocation',
      description:
        'Priority-based intelligent order line allocation across multiple fulfillment centers without double-booking or inventory fragmentation.',
    },
    {
      icon: <Timer className="w-5 h-5 text-amber-600 dark:text-amber-400" />,
      title: 'Serialized Reservations',
      description:
        'Distributed reservation locks with automated expiration workers, guaranteeing locked stock returns to the available pool if unfulfilled.',
    },
    {
      icon: <GitCommit className="w-5 h-5 text-[#456B1F] dark:text-[#C6F36B]" />,
      title: 'Immutable Double-Entry Ledger',
      description:
        'Every stock movement is an immutable double-entry transaction. Running balances are strictly derived from verifiable historical ledger deltas.',
    },
    {
      icon: <RotateCw className="w-5 h-5 text-[#147C68] dark:text-[#6CD8C0]" />,
      title: 'Scheduled MVCC Reconciliation',
      description:
        'Automated background sweeps execute snapshot audits under REPEATABLE READ isolation, detecting and isolating physical balance variances against double-entry ledger records.',
    },
    {
      icon: <Split className="w-5 h-5 text-[#147C68] dark:text-[#6CD8C0]" />,
      title: 'Atomic Inter-Warehouse Transfers',
      description:
        'Immediate and atomic stock relocation across facilities within a single ACID transaction, recording dual-entry ledger movements (Source OUT: -Q, 0 / Destination IN: +Q, 0) with zero in-transit drift.',
    },
    {
      icon: <Fingerprint className="w-5 h-5 text-[#456B1F] dark:text-[#C6F36B]" />,
      title: 'Cryptographic Audit Trail',
      description:
        'Comprehensive immutable audit logging for every lifecycle event, state transition, and administrative intervention with user attribution.',
    },
  ];

  return (
    <section id="capabilities" className="py-24 px-6 max-w-7xl mx-auto border-t border-[#D9E1D7] dark:border-[#26362E] transition-colors">
      <div className="text-center space-y-3 mb-16">
        <span className="text-xs font-mono uppercase tracking-wider text-[#456B1F] dark:text-[#C6F36B] font-bold">
          Architectural Core
        </span>
        <h2 className="text-3xl sm:text-4xl font-extrabold text-[#18231D] dark:text-[#EEF3EA] tracking-tight">
          Engineered for mission-critical physical operations
        </h2>
        <p className="text-sm text-[#657369] dark:text-[#9AA99E] max-w-xl mx-auto">
          Built without fragile microservice orchestration or out-of-process caching layers. Every invariant is backed by database guarantees.
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {features.map((feature, idx) => (
          <Card key={idx} className="space-y-3.5 hover:border-[#456B1F]/40 dark:hover:border-[#C6F36B]/40 transition-all">
            <div className="w-10 h-10 rounded-xl bg-[#E9EEE6] dark:bg-[#1B2922] border border-[#D9E1D7] dark:border-[#26362E] flex items-center justify-center">
              {feature.icon}
            </div>
            <h3 className="text-base font-bold text-[#18231D] dark:text-[#EEF3EA] tracking-tight">{feature.title}</h3>
            <p className="text-xs text-[#657369] dark:text-[#9AA99E] leading-relaxed">{feature.description}</p>
          </Card>
        ))}
      </div>
    </section>
  );
};
