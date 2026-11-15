import React from 'react';
import { Navigate, Outlet } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { Sidebar } from './Sidebar';
import { OrgSwitcher } from './OrgSwitcher';
import { LoadingSpinner } from '../common/LoadingSpinner';
import { LogOut, User as UserIcon } from 'lucide-react';

export const AppShell: React.FC = () => {
  const { user, isAuthenticated, isLoading, logout } = useAuth();

  if (isLoading) {
    return (
      <div className="min-h-screen bg-[#080c14] flex items-center justify-center">
        <LoadingSpinner message="Verifying session and security context..." size="lg" />
      </div>
    );
  }

  if (!isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return (
    <div className="flex h-screen bg-[#080c14] text-slate-100 overflow-hidden">
      {/* Sidebar navigation */}
      <Sidebar />

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
        {/* Top Header */}
        <header className="h-16 shrink-0 bg-[#0b101d]/90 backdrop-blur border-b border-slate-800 flex items-center justify-between px-6 z-10">
          <div className="flex items-center gap-4">
            <OrgSwitcher />
          </div>

          <div className="flex items-center gap-4">
            {/* User Profile and Logout */}
            <div className="flex items-center gap-3 pl-4 border-l border-slate-800">
              <div className="w-8 h-8 rounded-full bg-slate-800 border border-slate-700 flex items-center justify-center text-slate-300">
                <UserIcon className="w-4 h-4" />
              </div>
              <div className="hidden sm:flex flex-col text-left">
                <span className="text-xs font-semibold text-slate-200">
                  {user?.fullName || user?.email}
                </span>
                <span className="text-[10px] text-slate-400 font-mono">
                  {user?.email}
                </span>
              </div>
              <button
                onClick={logout}
                title="Sign out of StockGuard session"
                className="p-2 text-slate-400 hover:text-rose-400 hover:bg-rose-500/10 rounded-lg transition-colors cursor-pointer"
              >
                <LogOut className="w-4 h-4" />
              </button>
            </div>
          </div>
        </header>

        {/* Scrollable Page Content */}
        <main className="flex-1 overflow-y-auto p-6 lg:p-8 bg-grid-pattern relative">
          <Outlet />
        </main>
      </div>
    </div>
  );
};
