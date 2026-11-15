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
      icon: <Boxes className="w-5 h-5 text-cyan-400" />,
      title: 'Multi-Warehouse Allocation',
      description:
        'Priority-based intelligent order line allocation across multiple fulfillment centers without double-booking or inventory fragmentation.',
    },
    {
      icon: <Timer className="w-5 h-5 text-indigo-400" />,
      title: 'Serialized Reservations',
      description:
        'Distributed reservation locks with automated expiration workers, guaranteeing locked stock returns to available pool if unfulfilled.',
    },
    {
      icon: <GitCommit className="w-5 h-5 text-emerald-400" />,
      title: 'Append-Only Ledger',
      description:
        'Every stock movement is an immutable double-entry transaction. Running balances are strictly derived from verifiable historical ledger deltas.',
    },
    {
      icon: <RotateCw className="w-5 h-5 text-amber-400" />,
      title: 'Continuous MVCC Sweeps',
      description:
        'Automated background sweeps detect and isolate phantom discrepancies, ensuring physical on-hand equals ledger records 24/7.',
    },
    {
      icon: <Split className="w-5 h-5 text-teal-400" />,
      title: 'Inter-Warehouse Transfers',
      description:
        'Atomic two-phase stock relocation between regional facilities with dual-sided ledger accountability and zero in-transit loss.',
    },
    {
      icon: <Fingerprint className="w-5 h-5 text-rose-400" />,
      title: 'Cryptographic Audit Trail',
      description:
        'Comprehensive immutable audit logging for every lifecycle event, state transition, and administrative intervention with user attribution.',
    },
  ];

  return (
    <section id="capabilities" className="py-24 px-6 max-w-7xl mx-auto border-t border-slate-900">
      <div className="text-center space-y-3 mb-16">
        <span className="text-xs font-mono uppercase tracking-wider text-cyan-400 font-semibold">
          Architectural Core
        </span>
        <h2 className="text-3xl sm:text-4xl font-extrabold text-white tracking-tight">
          Engineered for mission-critical physical operations
        </h2>
        <p className="text-sm text-slate-400 max-w-xl mx-auto">
          Built without fragile microservice orchestration or out-of-process caching layers. Every invariant is backed by database guarantees.
        </p>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
        {features.map((feature, idx) => (
          <Card key={idx} className="space-y-3.5 hover:border-slate-700">
            <div className="w-10 h-10 rounded-xl bg-slate-900 border border-slate-800 flex items-center justify-center">
              {feature.icon}
            </div>
            <h3 className="text-base font-bold text-white tracking-tight">{feature.title}</h3>
            <p className="text-xs text-slate-400 leading-relaxed">{feature.description}</p>
          </Card>
        ))}
      </div>
    </section>
  );
};
