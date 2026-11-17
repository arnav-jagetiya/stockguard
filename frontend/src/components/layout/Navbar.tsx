import React from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { ShieldCheck, ArrowRight, Activity, UserPlus, Sparkles } from 'lucide-react';
import { Button } from '../common/Button';
import { ThemeTray } from '../common/ThemeTray';

export const Navbar: React.FC = () => {
  const { isAuthenticated } = useAuth();

  return (
    <nav className="fixed top-0 left-0 right-0 z-40 bg-white/85 dark:bg-[#0B100F]/85 backdrop-blur-md border-b border-[#D9E1D7] dark:border-[#26362E] transition-colors">
      <div className="max-w-7xl mx-auto px-6 h-16 flex items-center justify-between">
        {/* Brand */}
        <Link to="/" className="flex items-center gap-3 group">
          <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-[#456B1F] to-[#147C68] dark:from-[#C6F36B] dark:to-[#6CD8C0] flex items-center justify-center text-white dark:text-[#0B100F] font-bold shadow-md shadow-[#C6F36B]/20 group-hover:shadow-[0_0_20px_rgba(198,243,107,0.4)] transition-all">
            <ShieldCheck className="w-5 h-5 stroke-[2.5]" />
          </div>
          <div className="flex flex-col">
            <div className="flex items-center gap-2">
              <span className="font-bold text-sm tracking-wider text-[#18231D] dark:text-[#EEF3EA]">
                STOCKGUARD
              </span>
              <span className="text-[9px] font-mono px-1.5 py-0.5 rounded bg-[#E9EEE6] dark:bg-[#C6F36B]/15 text-[#456B1F] dark:text-[#C6F36B] border border-[#D9E1D7] dark:border-[#C6F36B]/30 font-semibold">
                LIVING WAREHOUSE
              </span>
            </div>
          </div>
        </Link>

        {/* Anchor Navigation */}
        <div className="hidden md:flex items-center gap-8 text-xs font-semibold text-[#657369] dark:text-[#9AA99E]">
          <a href="#spatial-warehouse" className="hover:text-[#456B1F] dark:hover:text-[#C6F36B] transition-colors">
            Spatial Warehouse
          </a>
          <a href="#capabilities" className="hover:text-[#456B1F] dark:hover:text-[#C6F36B] transition-colors">
            Capabilities
          </a>
          <a href="#storytelling" className="hover:text-[#456B1F] dark:hover:text-[#C6F36B] transition-colors">
            Storytelling Lab
          </a>
          <a href="#integrity" className="hover:text-[#456B1F] dark:hover:text-[#C6F36B] transition-colors">
            Zero-Drift Integrity
          </a>
          <a href="#architecture" className="hover:text-[#456B1F] dark:hover:text-[#C6F36B] transition-colors">
            Architecture
          </a>
        </div>

        {/* CTA & Theme Controls */}
        <div className="flex items-center gap-2.5">
          <ThemeTray />

          <a
            href="#storytelling"
            className="hidden sm:inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-[#18231D] dark:text-[#EEF3EA] bg-[#E9EEE6] dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] hover:border-[#657369]/40 dark:hover:border-[#9AA99E]/40 transition-colors"
          >
            <Activity className="w-3.5 h-3.5 text-[#456B1F] dark:text-[#C6F36B]" />
            Storytelling
          </a>

          {isAuthenticated ? (
            <Link to="/app/dashboard">
              <Button size="sm" icon={<ArrowRight className="w-3.5 h-3.5" />}>
                Operations Console
              </Button>
            </Link>
          ) : (
            <div className="flex items-center gap-2">
              <Link to="/login?demo=true">
                <Button
                  size="sm"
                  variant="outline"
                  icon={<Sparkles className="w-3.5 h-3.5 text-[#456B1F] dark:text-[#C6F36B]" />}
                  className="hidden sm:inline-flex border-[#456B1F]/30 dark:border-[#C6F36B]/30 text-[#456B1F] dark:text-[#C6F36B] hover:bg-[#E9EEE6] dark:hover:bg-[#C6F36B]/10 font-semibold"
                >
                  Explore Live Demo
                </Button>
              </Link>
              <Link to="/login">
                <Button size="sm" variant="secondary">
                  Sign In
                </Button>
              </Link>
              <Link to="/register">
                <Button size="sm" variant="primary" icon={<UserPlus className="w-3.5 h-3.5" />}>
                  Register
                </Button>
              </Link>
            </div>
          )}
        </div>
      </div>
    </nav>
  );
};
