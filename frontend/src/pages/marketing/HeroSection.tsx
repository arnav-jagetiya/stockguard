import React, { lazy, Suspense } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '../../components/common/Button';
import { ArrowRight, Terminal, CheckCircle2 } from 'lucide-react';

const WarehouseVisualizer = lazy(() =>
  import('../../components/3d/WarehouseVisualizer').then((m) => ({ default: m.WarehouseVisualizer }))
);

export const HeroSection: React.FC = () => {
  return (
    <section className="relative pt-32 pb-20 px-6 max-w-7xl mx-auto overflow-hidden">
      {/* Background Radial Glows */}
      <div className="absolute top-1/4 left-1/2 -translate-x-1/2 w-[700px] h-[500px] bg-gradient-to-b from-cyan-500/10 via-indigo-500/5 to-transparent rounded-full blur-3xl pointer-events-none -z-10" />

      <div className="text-center space-y-6 max-w-3xl mx-auto">
        {/* Release Pill */}
        <div className="inline-flex items-center gap-2 px-3 py-1.5 rounded-full bg-slate-100 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-xs text-slate-700 dark:text-slate-300 shadow-xs dark:shadow-inner">
          <span className="w-2 h-2 rounded-full bg-cyan-500 dark:bg-cyan-400 animate-pulse" />
          <span className="font-mono text-cyan-700 dark:text-cyan-300">STOCKGUARD KERNEL v6.2 ACCEPTED</span>
          <span className="text-slate-400 dark:text-slate-500">|</span>
          <span className="text-slate-600 dark:text-slate-400">PostgreSQL ACID Truth</span>
        </div>

        {/* Editorial Headline */}
        <h1 className="text-4xl sm:text-6xl font-extrabold tracking-tight text-slate-900 dark:text-white leading-[1.12]">
          Enterprise inventory with{' '}
          <span className="bg-gradient-to-r from-cyan-600 via-teal-500 to-indigo-600 dark:from-cyan-400 dark:via-teal-300 dark:to-indigo-400 bg-clip-text text-transparent">
            mathematical integrity
          </span>{' '}
          and zero drift.
        </h1>

        {/* Value Proposition */}
        <p className="text-base sm:text-lg text-slate-600 dark:text-slate-400 max-w-2xl mx-auto leading-relaxed">
          The single-source-of-truth inventory platform designed for multi-warehouse operations. Built on PostgreSQL REPEATABLE READ snapshot isolation, serialized reservations, and automated continuous reconciliation.
        </p>

        {/* CTAs */}
        <div className="flex flex-wrap items-center justify-center gap-4 pt-2">
          <Link to="/register">
            <Button size="lg" variant="primary" icon={<ArrowRight className="w-4 h-4" />}>
              Create Organization Account
            </Button>
          </Link>
          <Link to="/login">
            <Button variant="secondary" size="lg">
              Sign In to Console
            </Button>
          </Link>
          <a href="#demo">
            <Button variant="ghost" size="lg" icon={<Terminal className="w-4 h-4 text-cyan-600 dark:text-cyan-400" />}>
              Explore Interactive Lab
            </Button>
          </a>
        </div>

        {/* Trust Badges */}
        <div className="pt-4 flex flex-wrap items-center justify-center gap-6 text-xs text-slate-600 dark:text-slate-400 font-mono">
          <span className="flex items-center gap-1.5">
            <CheckCircle2 className="w-4 h-4 text-emerald-600 dark:text-emerald-400" /> Zero Phantom Stock
          </span>
          <span className="flex items-center gap-1.5">
            <CheckCircle2 className="w-4 h-4 text-cyan-600 dark:text-cyan-400" /> MVCC Snapshot Isolation
          </span>
          <span className="flex items-center gap-1.5">
            <CheckCircle2 className="w-4 h-4 text-indigo-600 dark:text-indigo-400" /> Immutable Append-Only Ledger
          </span>
        </div>
      </div>

      {/* 3D Interactive Warehouse Visualizer Showcase */}
      <div className="mt-14 max-w-5xl mx-auto">
        <Suspense
          fallback={
            <div className="h-[440px] w-full rounded-2xl bg-slate-100 dark:bg-[#0b101d] border border-slate-200 dark:border-slate-800/80 flex items-center justify-center text-slate-500 font-mono text-xs animate-pulse">
              Initializing 3D Spatial Warehouse Visualization...
            </div>
          }
        >
          <WarehouseVisualizer />
        </Suspense>
      </div>
    </section>
  );
};
