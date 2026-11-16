import React, { useState } from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { Sidebar } from './Sidebar';
import { OrgSwitcher } from './OrgSwitcher';
import { ThemeTray } from '../common/ThemeTray';
import { LoadingSpinner } from '../common/LoadingSpinner';
import { LogOut, User as UserIcon, Menu } from 'lucide-react';

export const AppShell: React.FC = () => {
  const { user, isAuthenticated, isLoading, logout } = useAuth();
  const [isMobileMenuOpen, setIsMobileMenuOpen] = useState(false);

  if (isLoading) {
    return (
      <div className="min-h-screen bg-[#F4F5EF] dark:bg-[#0B100F] flex items-center justify-center">
        <LoadingSpinner message="Verifying session and security context..." size="lg" />
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return (
    <div className="flex h-screen bg-[#F4F5EF] dark:bg-[#0B100F] text-[#18231D] dark:text-[#EEF3EA] overflow-hidden transition-colors">
      {/* Sidebar navigation */}
      <Sidebar isOpen={isMobileMenuOpen} onClose={() => setIsMobileMenuOpen(false)} />

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
        {/* Top Header */}
        <header className="h-16 shrink-0 bg-white/90 dark:bg-[#111A17]/90 backdrop-blur border-b border-[#D9E1D7] dark:border-[#26362E] flex items-center justify-between px-4 sm:px-6 z-10 transition-colors">
          <div className="flex items-center gap-3">
            <button
              onClick={() => setIsMobileMenuOpen(true)}
              className="lg:hidden p-2 rounded-lg text-[#657369] hover:text-[#18231D] dark:text-[#9AA99E] dark:hover:text-[#EEF3EA] hover:bg-[#E9EEE6] dark:hover:bg-[#1B2922]"
              aria-label="Open navigation menu"
            >
              <Menu className="w-5 h-5" />
            </button>
            <OrgSwitcher />
          </div>

          <div className="flex items-center gap-3 sm:gap-4">
            {/* Theme Tray */}
            <ThemeTray />

            {/* User Profile and Logout */}
            <div className="flex items-center gap-2.5 sm:gap-3 pl-3 sm:pl-4 border-l border-[#D9E1D7] dark:border-[#26362E]">
              <div className="w-8 h-8 rounded-full bg-[#E9EEE6] dark:bg-[#1B2922] border border-[#D9E1D7] dark:border-[#26362E] flex items-center justify-center text-[#18231D] dark:text-[#EEF3EA]">
                <UserIcon className="w-4 h-4" />
              </div>
              <div className="hidden sm:flex flex-col text-left">
                <span className="text-xs font-semibold text-[#18231D] dark:text-[#EEF3EA]">
                  {user?.fullName || user?.email}
                </span>
                <span className="text-[10px] text-[#657369] dark:text-[#9AA99E] font-mono">
                  {user?.email}
                </span>
              </div>
              <button
                onClick={logout}
                title="Sign out of StockGuard session"
                className="p-2 text-[#657369] hover:text-rose-600 hover:bg-rose-50 dark:text-[#9AA99E] dark:hover:text-rose-400 dark:hover:bg-rose-950/20 rounded-lg transition-colors cursor-pointer"
              >
                <LogOut className="w-4 h-4" />
              </button>
            </div>
          </div>
        </header>

        {/* Scrollable Page Content */}
        <main className="flex-1 overflow-y-auto p-4 sm:p-6 lg:p-8 bg-[#F4F5EF] dark:bg-[#0B100F] transition-colors relative">
          <Outlet />
        </main>
      </div>
    </div>
  );
};
