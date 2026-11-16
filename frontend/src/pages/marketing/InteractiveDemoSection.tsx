import React from 'react';
import { InteractiveSimulator } from '../../components/3d/InteractiveSimulator';

export const InteractiveDemoSection: React.FC = () => {
  return (
    <section id="demo" className="py-24 px-6 max-w-7xl mx-auto border-t border-slate-200 dark:border-slate-800">
      <div className="text-center space-y-3 mb-12">
        <span className="text-xs font-mono uppercase tracking-wider text-cyan-600 dark:text-cyan-400 font-semibold">
          Live Verification Laboratory
        </span>
        <h2 className="text-3xl sm:text-4xl font-extrabold text-slate-900 dark:text-white tracking-tight">
          Test the reconciliation invariants yourself
        </h2>
        <p className="text-sm text-slate-600 dark:text-slate-400 max-w-xl mx-auto">
          Simulate a silent inventory balance drift and watch how StockGuard's advisory lock and snapshot verification algorithm automatically identify, isolate, and audit the discrepancy.
        </p>
      </div>

      <div className="max-w-4xl mx-auto">
        <InteractiveSimulator />
      </div>
    </section>
  );
};
