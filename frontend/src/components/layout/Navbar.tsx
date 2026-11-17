import React, { useState, useEffect, useRef } from 'react';
import { Link } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { ShieldCheck, ArrowRight, Sparkles, Menu, X, LogIn } from 'lucide-react';
import { Button } from '../common/Button';
import { ThemeTray } from '../common/ThemeTray';

export const Navbar: React.FC = () => {
  const { isAuthenticated } = useAuth();
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);
  const mobileMenuRef = useRef<HTMLDivElement>(null);
  const menuButtonRef = useRef<HTMLButtonElement>(null);

  // Close mobile drawer on Escape key and manage focus
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && isMobileMenuOpen) {
        setIsMobileMenuOpen(false);
        menuButtonRef.current?.focus();
      }
    };

    if (isMobileMenuOpen) {
      document.addEventListener('keydown', handleKeyDown);
      document.body.style.overflow = 'hidden';
    } else {
      document.body.style.overflow = '';
    }

    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      document.body.style.overflow = '';
    };
  }, [isMobileMenuOpen]);

  const navLinks = [
    { label: 'Spatial Twin', href: '#spatial-warehouse' },
    { label: 'Capabilities', href: '#capabilities' },
    { label: 'Storytelling Lab', href: '#storytelling' },
    { label: 'Zero-Drift', href: '#integrity' },
    { label: 'Architecture', href: '#architecture' },
  ];

  return (
    <nav className="fixed top-0 left-0 right-0 z-40 bg-white/90 dark:bg-[#0B100F]/90 backdrop-blur-md border-b border-[#D9E1D7] dark:border-[#26362E] transition-colors">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 h-16 flex items-center justify-between gap-4">
        {/* Brand */}
        <Link to="/" className="flex items-center gap-2.5 shrink-0 group" aria-label="StockGuard Home">
          <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-[#456B1F] to-[#147C68] dark:from-[#C6F36B] dark:to-[#6CD8C0] flex items-center justify-center text-white dark:text-[#0B100F] font-bold shadow-md shadow-[#C6F36B]/20 group-hover:shadow-[0_0_20px_rgba(198,243,107,0.4)] transition-all">
            <ShieldCheck className="w-5 h-5 stroke-[2.5]" />
          </div>
          <div className="flex items-center gap-2">
            <span className="font-bold text-sm tracking-wider text-[#18231D] dark:text-[#EEF3EA]">
              STOCKGUARD
            </span>
            <span className="hidden sm:inline-block text-[9px] font-mono px-1.5 py-0.5 rounded bg-[#E9EEE6] dark:bg-[#C6F36B]/15 text-[#456B1F] dark:text-[#C6F36B] border border-[#D9E1D7] dark:border-[#C6F36B]/30 font-semibold tracking-wide">
              LIVING WAREHOUSE
            </span>
          </div>
        </Link>

        {/* Desktop Anchor Navigation — spaced, uncrowded, non-wrapping */}
        <div className="hidden lg:flex items-center gap-6 xl:gap-8 text-xs font-semibold text-[#657369] dark:text-[#9AA99E]">
          {navLinks.map((link) => (
            <a
              key={link.href}
              href={link.href}
              className="hover:text-[#456B1F] dark:hover:text-[#C6F36B] transition-colors whitespace-nowrap"
            >
              {link.label}
            </a>
          ))}
        </div>

        {/* CTA & Theme Controls */}
        <div className="flex items-center gap-2 sm:gap-3">
          <ThemeTray />

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
                  variant="primary"
                  icon={<Sparkles className="w-3.5 h-3.5 text-white dark:text-[#0B100F]" />}
                  className="whitespace-nowrap font-bold"
                >
                  <span className="hidden xs:inline">Explore Live</span> Demo
                </Button>
              </Link>
              <Link to="/login" className="hidden sm:inline-flex">
                <Button size="sm" variant="ghost" icon={<LogIn className="w-3.5 h-3.5" />}>
                  Sign In
                </Button>
              </Link>
            </div>
          )}

          {/* Mobile Navigation Toggle Button */}
          <button
            ref={menuButtonRef}
            onClick={() => setIsMobileMenuOpen(!isMobileMenuOpen)}
            className="lg:hidden p-2 rounded-lg text-[#657369] hover:text-[#18231D] dark:text-[#9AA99E] dark:hover:text-[#EEF3EA] hover:bg-[#E9EEE6] dark:hover:bg-[#1B2922] transition-colors"
            aria-expanded={isMobileMenuOpen}
            aria-label={isMobileMenuOpen ? 'Close navigation menu' : 'Open navigation menu'}
          >
            {isMobileMenuOpen ? <X className="w-5 h-5" /> : <Menu className="w-5 h-5" />}
          </button>
        </div>
      </div>

      {/* Mobile Navigation Drawer */}
      {isMobileMenuOpen && (
        <div
          ref={mobileMenuRef}
          role="dialog"
          aria-modal="true"
          aria-label="Mobile Navigation"
          className="lg:hidden fixed inset-x-0 top-16 bg-white/95 dark:bg-[#0B100F]/95 backdrop-blur-xl border-b border-[#D9E1D7] dark:border-[#26362E] px-6 py-6 shadow-2xl transition-all"
        >
          <div className="flex flex-col space-y-4">
            <span className="text-[10px] font-mono uppercase tracking-wider text-[#8A988E] dark:text-[#6C7D71] font-semibold">
              Platform Navigation
            </span>
            <div className="flex flex-col space-y-2">
              {navLinks.map((link) => (
                <a
                  key={link.href}
                  href={link.href}
                  onClick={() => setIsMobileMenuOpen(false)}
                  className="px-3 py-2 rounded-lg text-sm font-semibold text-[#18231D] dark:text-[#EEF3EA] hover:bg-[#E9EEE6] dark:hover:bg-[#16221E] transition-colors"
                >
                  {link.label}
                </a>
              ))}
            </div>

            <div className="pt-4 border-t border-[#D9E1D7] dark:border-[#26362E] flex flex-col gap-2.5">
              {isAuthenticated ? (
                <Link to="/app/dashboard" onClick={() => setIsMobileMenuOpen(false)}>
                  <Button className="w-full" size="md" icon={<ArrowRight className="w-4 h-4" />}>
                    Operations Console
                  </Button>
                </Link>
              ) : (
                <>
                  <Link to="/login?demo=true" onClick={() => setIsMobileMenuOpen(false)}>
                    <Button className="w-full" size="md" variant="primary" icon={<Sparkles className="w-4 h-4" />}>
                      Explore Live Demo (Acme Industrial)
                    </Button>
                  </Link>
                  <Link to="/login" onClick={() => setIsMobileMenuOpen(false)}>
                    <Button className="w-full" size="md" variant="outline" icon={<LogIn className="w-4 h-4" />}>
                      Sign In to Account
                    </Button>
                  </Link>
                </>
              )}
            </div>
          </div>
        </div>
      )}
    </nav>
  );
};
