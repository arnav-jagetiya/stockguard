import React from 'react';
import { NavLink } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import {
  LayoutDashboard,
  Package,
  Warehouse,
  Boxes,
  ShoppingCart,
  BookmarkCheck,
  ArrowLeftRight,
  ShieldAlert,
  ScrollText,
  Users,
  ShieldCheck,
  X,
} from 'lucide-react';

interface NavItem {
  name: string;
  path: string;
  icon: React.ReactNode;
  permission?: string;
  badge?: string;
}

interface SidebarProps {
  isOpen?: boolean;
  onClose?: () => void;
}

export const Sidebar: React.FC<SidebarProps> = ({ isOpen, onClose }) => {
  const { hasPermission } = useAuth();

  const navItems: NavItem[] = [
    {
      name: 'Overview',
      path: '/app/dashboard',
      icon: <LayoutDashboard className="w-4 h-4" />,
      permission: 'dashboard:view',
    },
    {
      name: 'Catalog',
      path: '/app/products',
      icon: <Package className="w-4 h-4" />,
      permission: 'product:view',
    },
    {
      name: 'Warehouses',
      path: '/app/warehouses',
      icon: <Warehouse className="w-4 h-4" />,
      permission: 'warehouse:view',
    },
    {
      name: 'Inventory & Ledger',
      path: '/app/inventory',
      icon: <Boxes className="w-4 h-4" />,
      permission: 'inventory:view',
    },
    {
      name: 'Orders',
      path: '/app/orders',
      icon: <ShoppingCart className="w-4 h-4" />,
      permission: 'order:view',
    },
    {
      name: 'Reservations',
      path: '/app/reservations',
      icon: <BookmarkCheck className="w-4 h-4" />,
      permission: 'reservation:view',
    },
    {
      name: 'Transfers',
      path: '/app/transfers',
      icon: <ArrowLeftRight className="w-4 h-4" />,
      permission: 'transfer:view',
    },
    {
      name: 'Reconciliation',
      path: '/app/reconciliation',
      icon: <ShieldAlert className="w-4 h-4" />,
      permission: 'inventory:discrepancy:view',
    },
    {
      name: 'Audit Trail',
      path: '/app/audit-logs',
      icon: <ScrollText className="w-4 h-4" />,
      permission: 'audit:view',
    },
    {
      name: 'Access & Team',
      path: '/app/team',
      icon: <Users className="w-4 h-4" />,
      permission: 'membership:view',
    },
  ];

  const visibleItems = navItems.filter((item) => !item.permission || hasPermission(item.permission));

  const sidebarContent = (
    <aside className="w-64 h-full bg-white dark:bg-[#111A17] border-r border-[#D9E1D7] dark:border-[#26362E] flex flex-col justify-between select-none transition-colors">
      <div>
        {/* Brand Header */}
        <div className="h-16 flex items-center justify-between px-6 border-b border-[#D9E1D7] dark:border-[#26362E]">
          <div className="flex items-center gap-3">
            <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-[#456B1F] to-[#147C68] dark:from-[#C6F36B] dark:to-[#6CD8C0] flex items-center justify-center text-white dark:text-[#0B100F] font-bold shadow-md shadow-[#C6F36B]/20">
              <ShieldCheck className="w-5 h-5 stroke-[2.5]" />
            </div>
            <div>
              <div className="flex items-center gap-1.5">
                <span className="font-bold text-sm tracking-wider text-[#18231D] dark:text-[#EEF3EA]">
                  STOCKGUARD
                </span>
                <span className="text-[9px] font-mono font-bold px-1 py-0.2 bg-[#E9EEE6] dark:bg-[#C6F36B]/15 text-[#456B1F] dark:text-[#C6F36B] rounded border border-[#D9E1D7] dark:border-[#C6F36B]/30">
                  ENTERPRISE
                </span>
              </div>
              <p className="text-[10px] text-[#657369] dark:text-[#9AA99E] font-mono">v6.2 · PostgreSQL MVCC</p>
            </div>
          </div>
          {onClose && (
            <button
              onClick={onClose}
              className="lg:hidden p-1.5 rounded-lg text-[#8A988E] hover:text-[#18231D] dark:hover:text-[#EEF3EA] hover:bg-[#E9EEE6] dark:hover:bg-[#1B2922]"
            >
              <X className="w-5 h-5" />
            </button>
          )}
        </div>

        {/* Navigation list */}
        <div className="px-3 py-4 space-y-1">
          <div className="px-3 pb-2 text-[10px] font-semibold text-[#8A988E] dark:text-[#6C7D71] uppercase tracking-wider font-mono">
            Command Modules
          </div>
          {visibleItems.map((item) => (
            <NavLink
              key={item.path}
              to={item.path}
              onClick={onClose}
              className={({ isActive }) =>
                `flex items-center justify-between px-3 py-2 rounded-lg text-xs font-medium transition-colors ${
                  isActive
                    ? 'bg-[#E9EEE6] dark:bg-[#C6F36B]/15 text-[#456B1F] dark:text-[#C6F36B] border border-[#456B1F]/30 dark:border-[#C6F36B]/30 font-semibold shadow-xs'
                    : 'text-[#657369] dark:text-[#9AA99E] hover:text-[#18231D] dark:hover:text-[#EEF3EA] hover:bg-[#F9FAF7] dark:hover:bg-[#182621]'
                }`
              }
            >
              <div className="flex items-center gap-3">
                <span className="shrink-0">{item.icon}</span>
                <span>{item.name}</span>
              </div>
              {item.badge && (
                <span className="px-1.5 py-0.5 rounded text-[10px] bg-[#E9EEE6] dark:bg-[#1B2922] text-[#18231D] dark:text-[#EEF3EA] font-mono">
                  {item.badge}
                </span>
              )}
            </NavLink>
          ))}
        </div>
      </div>

      {/* System Status Footer */}
      <div className="p-4 border-t border-[#D9E1D7] dark:border-[#26362E]">
        <div className="p-3 rounded-xl bg-[#F0F4EE] dark:bg-[#16221E] border border-[#D9E1D7] dark:border-[#26362E] space-y-2">
          <div className="flex items-center justify-between text-[11px]">
            <span className="text-[#657369] dark:text-[#9AA99E] font-medium">Engine Telemetry</span>
            <span className="flex items-center gap-1.5 text-[#456B1F] dark:text-[#C6F36B] font-mono text-[10px] font-semibold">
              <span className="w-1.5 h-1.5 rounded-full bg-[#456B1F] dark:bg-[#C6F36B] animate-pulse" />
              SYNCHRONIZED
            </span>
          </div>
          <div className="text-[10px] text-[#8A988E] dark:text-[#6C7D71] font-mono">
            Isolation: REPEATABLE READ
          </div>
        </div>
      </div>
    </aside>
  );

  return (
    <>
      {/* Desktop static sidebar */}
      <div className="hidden lg:flex shrink-0">
        {sidebarContent}
      </div>

      {/* Mobile drawer overlay */}
      {isOpen && (
        <div className="lg:hidden fixed inset-0 z-50 flex">
          <div
            className="fixed inset-0 bg-black/75 backdrop-blur-sm animate-in fade-in"
            onClick={onClose}
          />
          <div className="relative z-10 animate-in slide-in-from-left duration-200">
            {sidebarContent}
          </div>
        </div>
      )}
    </>
  );
};
