import React, { useState } from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { Sidebar } from './Sidebar';
import { OrgSwitcher } from './OrgSwitcher';
import { ThemeSelector } from '../common/ThemeSelector';
import { LoadingSpinner } from '../common/LoadingSpinner';
import { LogOut, User as UserIcon, Menu } from 'lucide-react';

export const AppShell: React.FC = () => {
  const { user, isAuthenticated, isLoading, logout } = useAuth();
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);

  if (isLoading) {
    return (
      <div className="min-h-screen bg-slate-50 dark:bg-[#080c14] flex items-center justify-center">
        <LoadingSpinner message="Verifying session and security context..." size="lg" />
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return (
    <div className="flex h-screen bg-slate-50 dark:bg-[#080c14] text-slate-900 dark:text-slate-100 overflow-hidden transition-colors">
      {/* Sidebar navigation */}
      <Sidebar isOpen={isMobileMenuOpen} onClose={() => setIsMobileMenuOpen(false)} />

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
        {/* Top Header */}
        <header className="h-16 shrink-0 bg-white/90 dark:bg-[#0b101d]/90 backdrop-blur border-b border-slate-200 dark:border-slate-800 flex items-center justify-between px-4 sm:px-6 z-10 transition-colors">
          <div className="flex items-center gap-3">
            <button
              onClick={() => setIsMobileMenuOpen(true)}
              className="lg:hidden p-2 rounded-lg text-slate-500 hover:text-slate-700 dark:text-slate-400 dark:hover:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800"
              aria-label="Open navigation menu"
            >
              <Menu className="w-5 h-5" />
            </button>
            <OrgSwitcher />
          </div>

          <div className="flex items-center gap-3 sm:gap-4">
            {/* Theme Selector */}
            <ThemeSelector />

            {/* User Profile and Logout */}
            <div className="flex items-center gap-2.5 sm:gap-3 pl-3 sm:pl-4 border-l border-slate-200 dark:border-slate-800">
              <div className="w-8 h-8 rounded-full bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 flex items-center justify-center text-slate-600 dark:text-slate-300">
                <UserIcon className="w-4 h-4" />
              </div>
              <div className="hidden sm:flex flex-col text-left">
                <span className="text-xs font-semibold text-slate-800 dark:text-slate-200">
                  {user?.fullName || user?.email}
                </span>
                <span className="text-[10px] text-slate-500 dark:text-slate-400 font-mono">
                  {user?.email}
                </span>
              </div>
              <button
                onClick={logout}
                title="Sign out of StockGuard session"
                className="p-2 text-slate-500 hover:text-rose-600 hover:bg-rose-50 dark:text-slate-400 dark:hover:text-rose-400 dark:hover:bg-rose-500/10 rounded-lg transition-colors cursor-pointer"
              >
                <LogOut className="w-4 h-4" />
              </button>
            </div>
          </div>
        </header>

        {/* Scrollable Page Content */}
        <main className="flex-1 overflow-y-auto p-4 sm:p-6 lg:p-8 bg-grid-pattern relative">
          <Outlet />
        </main>
      </div>
    </div>
  );
};
