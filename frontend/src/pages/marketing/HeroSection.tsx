import React, { lazy, Suspense } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '../../components/common/Button';
import { Terminal, CheckCircle2, ShieldCheck, Cpu, Sparkles } from 'lucide-react';

const WarehouseVisualizer = lazy(() =>
  import('../../components/3d/WarehouseVisualizer').then((m) => ({ default: m.WarehouseVisualizer }))
);

export const HeroSection: React.FC = () => {
  return (
    <section id="spatial-warehouse" className="relative pt-32 pb-20 px-6 max-w-7xl mx-auto overflow-hidden">
      {/* Background Radial Ambient Glow */}
      <div className="absolute top-1/6 left-1/2 -translate-x-1/2 w-[800px] h-[550px] bg-gradient-to-b from-[#456B1F]/15 dark:from-[#C6F36B]/12 via-[#147C68]/10 dark:via-[#6CD8C0]/08 to-transparent rounded-full blur-3xl pointer-events-none -z-10" />

      <div className="text-center space-y-6 max-w-4xl mx-auto">
        {/* Release Pill */}
        <div className="inline-flex items-center gap-2 px-3.5 py-1.5 rounded-full bg-[#E9EEE6] dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] text-xs text-[#18231D] dark:text-[#EEF3EA] shadow-xs">
          <span className="w-2 h-2 rounded-full bg-[#456B1F] dark:bg-[#C6F36B] animate-pulse" />
          <span className="font-mono font-bold text-[#456B1F] dark:text-[#C6F36B]">THE LIVING WAREHOUSE · KERNEL v6.2</span>
          <span className="text-[#8A988E] dark:text-[#6C7D71]">|</span>
          <span className="text-[#657369] dark:text-[#9AA99E] font-medium">PostgreSQL ACID Truth</span>
        </div>

        {/* Editorial Headline */}
        <h1 className="text-4xl sm:text-6xl lg:text-7xl font-extrabold tracking-tight text-[#18231D] dark:text-[#EEF3EA] leading-[1.08]">
          Inventory integrity.{' '}
          <span className="bg-gradient-to-r from-[#456B1F] via-[#147C68] to-[#2E4A15] dark:from-[#C6F36B] dark:via-[#6CD8C0] dark:to-[#E8F8B6] bg-clip-text text-transparent">
            Engineered in real time.
          </span>
        </h1>

        {/* Supporting Message */}
        <p className="text-base sm:text-lg text-[#657369] dark:text-[#9AA99E] max-w-2xl mx-auto leading-relaxed">
          Control every stock movement, serialized reservation, and facility operation with an enterprise platform architected for absolute mathematical accuracy under high concurrency.
        </p>

        {/* CTAs */}
        <div className="flex flex-wrap items-center justify-center gap-4 pt-2">
          <Link to="/login?demo=true">
            <Button size="lg" variant="primary" icon={<Sparkles className="w-4 h-4" />}>
              Explore Live Demo
            </Button>
          </Link>
          <a href="#storytelling">
            <Button variant="secondary" size="lg" icon={<Terminal className="w-4 h-4 text-[#456B1F] dark:text-[#C6F36B]" />}>
              Storytelling Lab
            </Button>
          </a>
          <Link to="/login">
            <Button variant="ghost" size="lg">
              Sign In
            </Button>
          </Link>
        </div>

        {/* Industrial Guarantees Trust Badges */}
        <div className="pt-4 flex flex-wrap items-center justify-center gap-6 text-xs text-[#657369] dark:text-[#9AA99E] font-mono">
          <span className="flex items-center gap-1.5">
            <CheckCircle2 className="w-4 h-4 text-[#456B1F] dark:text-[#C6F36B]" /> Zero Phantom Stock
          </span>
          <span className="flex items-center gap-1.5">
            <Cpu className="w-4 h-4 text-[#147C68] dark:text-[#6CD8C0]" /> REPEATABLE READ Isolation
          </span>
          <span className="flex items-center gap-1.5">
            <ShieldCheck className="w-4 h-4 text-[#456B1F] dark:text-[#C6F36B]" /> Immutable Double-Entry Ledger
          </span>
        </div>
      </div>

      {/* 3D Spatial Warehouse Visualization Showcase */}
      <div className="mt-12 max-w-6xl mx-auto">
        <Suspense
          fallback={
            <div className="h-[520px] w-full rounded-2xl bg-[#E9EEE6] dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] flex items-center justify-center text-[#657369] dark:text-[#9AA99E] font-mono text-xs animate-pulse">
              Initializing Spatial Computing Warehouse Simulation...
            </div>
          }
        >
          <WarehouseVisualizer />
        </Suspense>
      </div>
    </section>
  );
};
