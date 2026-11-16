import React, { useState } from 'react';
import { Button } from '../common/Button';
import { Badge } from '../common/Badge';
import {
  ShieldCheck,
  Zap,
  RotateCcw,
  Play,
  Database,
  Lock,
} from 'lucide-react';

interface SimulationState {
  balanceOnHand: number;
  ledgerOnHand: number;
  sweepStatus: 'IDLE' | 'RUNNING' | 'DETECTED' | 'RESOLVED';
  discrepancyCount: number;
  consecutiveDetections: number;
  activeLock: boolean;
  logs: string[];
}

export const InteractiveSimulator: React.FC = () => {
  const [state, setState] = useState<SimulationState>({
    balanceOnHand: 150,
    ledgerOnHand: 150,
    sweepStatus: 'IDLE',
    discrepancyCount: 0,
    consecutiveDetections: 0,
    activeLock: false,
    logs: ['[00:00:00] Engine initialized. REPEATABLE READ snapshot established. All balances synchronized.'],
  });

  const [step, setStep] = useState<number>(0);

  const appendLog = (msg: string) => {
    const time = new Date().toLocaleTimeString();
    setState((prev) => ({
      ...prev,
      logs: [`[${time}] ${msg}`, ...prev.logs.slice(0, 5)],
    }));
  };

  const handleInjectDelta = () => {
    setState((prev) => ({
      ...prev,
      balanceOnHand: 150,
      ledgerOnHand: 125, // 25 units missing in ledger
      sweepStatus: 'IDLE',
    }));
    setStep(1);
    appendLog('SIMULATION: Rogue database mutation executed. Physical balance=150, Ledger sum=125. (DELTA: +25)');
  };

  const handleRunReconciliation = () => {
    setState((prev) => ({ ...prev, activeLock: true, sweepStatus: 'RUNNING' }));
    appendLog('SWEEP: Advisory lock pg_try_advisory_xact_lock(0x5354) acquired.');

    setTimeout(() => {
      setState((prev) => ({
        ...prev,
        activeLock: false,
        sweepStatus: 'DETECTED',
        discrepancyCount: 1,
        consecutiveDetections: prev.consecutiveDetections + 1,
      }));
      setStep(2);
      appendLog('DETECTED: DELTA_MISMATCH identified on SKU-7829 at WH-01. Delta: +25. Consecutive count: 1.');
    }, 600);
  };

  const handleResolveDiscrepancy = () => {
    setState((prev) => ({
      ...prev,
      ledgerOnHand: 150, // corrected
      sweepStatus: 'RESOLVED',
      discrepancyCount: 0,
    }));
    setStep(3);
    appendLog('AUDIT: Discrepancy resolved by Operator. Compensating ledger entry #1092 logged. Status: RESOLVED.');
  };

  const handleReset = () => {
    setState({
      balanceOnHand: 150,
      ledgerOnHand: 150,
      sweepStatus: 'IDLE',
      discrepancyCount: 0,
      consecutiveDetections: 0,
      activeLock: false,
      logs: ['[RESET] Engine reset to pristine state. All ledger entries match inventory balances.'],
    });
    setStep(0);
  };

  return (
    <div className="rounded-2xl bg-white dark:bg-[#0b1120] border border-slate-200 dark:border-slate-800 p-6 md:p-8 shadow-xl dark:shadow-2xl relative overflow-hidden transition-colors">
      <div className="flex flex-col md:flex-row md:items-center justify-between gap-4 pb-6 border-b border-slate-200 dark:border-slate-800">
        <div>
          <div className="flex items-center gap-2">
            <h3 className="text-lg font-bold text-slate-900 dark:text-white tracking-tight">
              Interactive Engine Simulator
            </h3>
            <Badge variant="info">LIVE INTERACTIVE LAB</Badge>
          </div>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
            Experience real-time PostgreSQL MVCC reconciliation, advisory locking, and immutable audit logs.
          </p>
        </div>

        <Button variant="outline" size="sm" onClick={handleReset} icon={<RotateCcw className="w-3.5 h-3.5" />}>
          Reset Engine
        </Button>
      </div>

      {/* Simulator Stepper HUD */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-3 my-6">
        <div
          className={`p-3.5 rounded-xl border transition-all ${
            step === 0
              ? 'bg-cyan-50 dark:bg-cyan-500/10 border-cyan-400 dark:border-cyan-500/40 text-cyan-800 dark:text-cyan-300'
              : 'bg-slate-50 dark:bg-[#0f172a] border-slate-200 dark:border-slate-800 text-slate-500 dark:text-slate-400'
          }`}
        >
          <span className="text-[10px] font-mono font-bold block mb-1">STAGE 1</span>
          <p className="text-xs font-semibold text-slate-900 dark:text-white">Pristine State</p>
          <p className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">Balance = Ledger</p>
        </div>

        <div
          className={`p-3.5 rounded-xl border transition-all ${
            step === 1
              ? 'bg-amber-50 dark:bg-amber-500/10 border-amber-400 dark:border-amber-500/40 text-amber-800 dark:text-amber-300'
              : 'bg-slate-50 dark:bg-[#0f172a] border-slate-200 dark:border-slate-800 text-slate-500 dark:text-slate-400'
          }`}
        >
          <span className="text-[10px] font-mono font-bold block mb-1">STAGE 2</span>
          <p className="text-xs font-semibold text-slate-900 dark:text-white">Delta Injected</p>
          <p className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">Physical &ne; Ledger</p>
        </div>

        <div
          className={`p-3.5 rounded-xl border transition-all ${
            step === 2
              ? 'bg-rose-50 dark:bg-rose-500/10 border-rose-400 dark:border-rose-500/40 text-rose-800 dark:text-rose-300'
              : 'bg-slate-50 dark:bg-[#0f172a] border-slate-200 dark:border-slate-800 text-slate-500 dark:text-slate-400'
          }`}
        >
          <span className="text-[10px] font-mono font-bold block mb-1">STAGE 3</span>
          <p className="text-xs font-semibold text-slate-900 dark:text-white">Reconciliation Sweep</p>
          <p className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">Discrepancy flagged</p>
        </div>

        <div
          className={`p-3.5 rounded-xl border transition-all ${
            step === 3
              ? 'bg-emerald-50 dark:bg-emerald-500/10 border-emerald-400 dark:border-emerald-500/40 text-emerald-800 dark:text-emerald-300'
              : 'bg-slate-50 dark:bg-[#0f172a] border-slate-200 dark:border-slate-800 text-slate-500 dark:text-slate-400'
          }`}
        >
          <span className="text-[10px] font-mono font-bold block mb-1">STAGE 4</span>
          <p className="text-xs font-semibold text-slate-900 dark:text-white">Resolved & Audited</p>
          <p className="text-[11px] text-slate-500 dark:text-slate-400 mt-0.5">Immutable resolution</p>
        </div>
      </div>

      {/* Live State HUD */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 p-4 rounded-xl bg-slate-50 dark:bg-[#070b14] border border-slate-200 dark:border-slate-800/90 font-mono">
        <div>
          <span className="text-[10px] text-slate-500 block uppercase">Balance On-Hand</span>
          <span className="text-xl font-bold text-slate-900 dark:text-slate-100">{state.balanceOnHand} units</span>
        </div>
        <div>
          <span className="text-[10px] text-slate-500 block uppercase">Ledger Sum</span>
          <span className={`text-xl font-bold ${state.balanceOnHand !== state.ledgerOnHand ? 'text-amber-600 dark:text-amber-400' : 'text-emerald-600 dark:text-emerald-400'}`}>
            {state.ledgerOnHand} units
          </span>
        </div>
        <div>
          <span className="text-[10px] text-slate-500 block uppercase">Active Discrepancies</span>
          <span className={`text-xl font-bold ${state.discrepancyCount > 0 ? 'text-rose-600 dark:text-rose-400' : 'text-slate-400'}`}>
            {state.discrepancyCount}
          </span>
        </div>
        <div>
          <span className="text-[10px] text-slate-500 block uppercase">Advisory Lock</span>
          <span className="text-sm font-bold flex items-center gap-1.5 mt-1 text-cyan-600 dark:text-cyan-400">
            {state.activeLock ? <Lock className="w-4 h-4 text-amber-500" /> : <Lock className="w-4 h-4 text-slate-400" />}
            {state.activeLock ? 'ACQUIRED' : 'RELEASED'}
          </span>
        </div>
      </div>

      {/* Simulator Action Controls */}
      <div className="flex flex-wrap items-center gap-3 my-6">
        <Button
          onClick={handleInjectDelta}
          disabled={step !== 0 && step !== 3}
          variant="secondary"
          icon={<Zap className="w-4 h-4 text-amber-500" />}
        >
          1. Inject Discrepancy (+25 Delta)
        </Button>

        <Button
          onClick={handleRunReconciliation}
          disabled={step !== 1}
          variant="primary"
          icon={<Play className="w-4 h-4" />}
        >
          2. Trigger MVCC Reconciliation
        </Button>

        <Button
          onClick={handleResolveDiscrepancy}
          disabled={step !== 2}
          variant="outline"
          className="hover:border-emerald-500/50 hover:text-emerald-600 dark:hover:text-emerald-300"
          icon={<ShieldCheck className="w-4 h-4 text-emerald-500" />}
        >
          3. Authorize Human Resolution
        </Button>
      </div>

      {/* Telemetry Console Stream */}
      <div className="p-4 rounded-xl bg-slate-900 border border-slate-800 font-mono text-[11px] space-y-1 shadow-inner text-slate-200">
        <div className="flex items-center justify-between text-slate-400 pb-2 border-b border-slate-800 text-[10px]">
          <span className="flex items-center gap-1.5">
            <Database className="w-3 h-3 text-cyan-400" /> KERNEL TELEMETRY STREAM
          </span>
          <span className="text-slate-400">POSTGRESQL 16 ACID</span>
        </div>
        <div className="pt-2 space-y-1.5 max-h-36 overflow-y-auto">
          {state.logs.map((log, i) => (
            <p
              key={i}
              className={`${
                log.includes('DETECTED')
                  ? 'text-rose-400'
                  : log.includes('AUDIT')
                  ? 'text-emerald-400'
                  : log.includes('SIMULATION')
                  ? 'text-amber-400'
                  : 'text-slate-300'
              }`}
            >
              {log}
            </p>
          ))}
        </div>
      </div>
    </div>
  );
};
