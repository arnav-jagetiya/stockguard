import React from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { ShieldCheck, ArrowRight, Activity } from 'lucide-react';
import { Button } from '../common/Button';
import { ThemeSelector } from '../common/ThemeSelector';

export const Navbar: React.FC = () => {
  const { isAuthenticated } = useAuth();

  return (
    <nav className="fixed top-0 left-0 right-0 z-40 bg-white/80 dark:bg-[#080c14]/80 backdrop-blur-md border-b border-slate-200 dark:border-slate-800/80 transition-colors">
      <div className="max-w-7xl mx-auto px-6 h-16 flex items-center justify-between">
        {/* Brand */}
        <Link to="/" className="flex items-center gap-3 group">
          <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-cyan-500 to-indigo-600 flex items-center justify-center text-slate-950 font-bold shadow-md shadow-cyan-500/20 group-hover:shadow-[0_0_20px_rgba(6,182,212,0.5)] transition-all">
            <ShieldCheck className="w-5 h-5 text-slate-950 stroke-[2.5]" />
          </div>
          <div className="flex flex-col">
            <div className="flex items-center gap-2">
              <span className="font-bold text-sm tracking-wider text-slate-900 dark:text-slate-100">
                STOCKGUARD
              </span>
              <span className="text-[9px] font-mono px-1.5 py-0.5 rounded bg-cyan-500/10 text-cyan-700 dark:text-cyan-400 border border-cyan-500/30">
                PLATFORM
              </span>
            </div>
          </div>
        </Link>

        {/* Anchor Navigation */}
        <div className="hidden md:flex items-center gap-8 text-xs font-medium text-slate-600 dark:text-slate-300">
          <a href="#capabilities" className="hover:text-cyan-600 dark:hover:text-cyan-400 transition-colors">
            Capabilities
          </a>
          <a href="#demo" className="hover:text-cyan-600 dark:hover:text-cyan-400 transition-colors">
            Interactive Demo
          </a>
          <a href="#integrity" className="hover:text-cyan-600 dark:hover:text-cyan-400 transition-colors">
            Reconciliation Engine
          </a>
          <a href="#architecture" className="hover:text-cyan-600 dark:hover:text-cyan-400 transition-colors">
            Architecture
          </a>
        </div>

        {/* CTA & Theme Controls */}
        <div className="flex items-center gap-3">
          <ThemeSelector />

          <a
            href="#demo"
            className="hidden sm:inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-slate-700 dark:text-slate-300 hover:text-slate-950 dark:hover:text-white bg-slate-100 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 hover:border-slate-300 dark:hover:border-slate-700 transition-colors"
          >
            <Activity className="w-3.5 h-3.5 text-cyan-600 dark:text-cyan-400" />
            Live Simulator
          </a>

          {isAuthenticated ? (
            <Link to="/app/dashboard">
              <Button size="sm" icon={<ArrowRight className="w-3.5 h-3.5" />}>
                Operations Console
              </Button>
            </Link>
          ) : (
            <Link to="/login">
              <Button size="sm" icon={<ArrowRight className="w-3.5 h-3.5" />}>
                Sign In
              </Button>
            </Link>
          )}
        </div>
      </div>
    </nav>
  );
};
