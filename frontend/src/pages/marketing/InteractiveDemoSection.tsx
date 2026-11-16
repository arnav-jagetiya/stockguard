import React from 'react';
import { InteractiveSimulator } from '../../components/3d/InteractiveSimulator';

export const InteractiveDemoSection: React.FC = () => {
  return (
    <section id="storytelling" className="py-24 px-6 max-w-7xl mx-auto border-t border-[#D9E1D7] dark:border-[#26362E] transition-colors">
      <div className="text-center space-y-3 mb-12">
        <span className="text-xs font-mono uppercase tracking-wider text-[#456B1F] dark:text-[#C6F36B] font-bold">
          Interactive Simulation Laboratory
        </span>
        <h2 className="text-3xl sm:text-4xl font-extrabold text-[#18231D] dark:text-[#EEF3EA] tracking-tight">
          Test the reconciliation invariants yourself
        </h2>
        <p className="text-sm text-[#657369] dark:text-[#9AA99E] max-w-xl mx-auto">
          Simulate an inventory balance drift and watch how StockGuard's advisory lock and snapshot verification algorithm automatically identify, isolate, and audit the discrepancy.
        </p>
      </div>

      <div className="max-w-4xl mx-auto">
        <InteractiveSimulator />
      </div>
    </section>
  );
};
