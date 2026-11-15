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
} from 'lucide-react';

interface NavItem {
  name: string;
  path: string;
  icon: React.ReactNode;
  permission?: string;
  badge?: string;
}

export const Sidebar: React.FC = () => {
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
      permission: 'inventory:reconcile',
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

  return (
    <aside className="w-64 shrink-0 bg-[#0b101d] border-r border-slate-800 flex flex-col justify-between select-none">
      <div>
        {/* Brand Header */}
        <div className="h-16 flex items-center px-6 border-b border-slate-800/80 gap-3">
          <div className="w-8 h-8 rounded-lg bg-gradient-to-br from-cyan-500 to-indigo-600 flex items-center justify-center text-slate-950 font-bold shadow-md shadow-cyan-500/20">
            <ShieldCheck className="w-5 h-5 text-slate-950 stroke-[2.5]" />
          </div>
          <div>
            <div className="flex items-center gap-1.5">
              <span className="font-bold text-sm tracking-wider text-slate-100">STOCKGUARD</span>
              <span className="text-[9px] font-mono font-semibold px-1 py-0.2 bg-cyan-500/20 text-cyan-300 rounded border border-cyan-500/30">
                ENTERPRISE
              </span>
            </div>
            <p className="text-[10px] text-slate-400 font-mono">v6.2 · PostgreSQL MVCC</p>
          </div>
        </div>

        {/* Navigation list */}
        <div className="px-3 py-4 space-y-1">
          <div className="px-3 pb-2 text-[10px] font-semibold text-slate-500 uppercase tracking-wider">
            Operations Platform
          </div>
          {visibleItems.map((item) => (
            <NavLink
              key={item.path}
              to={item.path}
              className={({ isActive }) =>
                `flex items-center justify-between px-3 py-2 rounded-lg text-xs font-medium transition-colors ${
                  isActive
                    ? 'bg-cyan-500/10 text-cyan-400 border border-cyan-500/30 font-semibold shadow-[0_0_15px_rgba(6,182,212,0.1)]'
                    : 'text-slate-400 hover:text-slate-100 hover:bg-slate-850/60'
                }`
              }
            >
              <div className="flex items-center gap-3">
                <span className="shrink-0">{item.icon}</span>
                <span>{item.name}</span>
              </div>
              {item.badge && (
                <span className="px-1.5 py-0.5 rounded text-[10px] bg-slate-800 text-slate-300 font-mono">
                  {item.badge}
                </span>
              )}
            </NavLink>
          ))}
        </div>
      </div>

      {/* System Status Footer */}
      <div className="p-4 border-t border-slate-800/80">
        <div className="p-3 rounded-xl bg-[#0e1628] border border-slate-800/80 space-y-2">
          <div className="flex items-center justify-between text-[11px]">
            <span className="text-slate-400 font-medium">Engine Status</span>
            <span className="flex items-center gap-1.5 text-emerald-400 font-mono text-[10px]">
              <span className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse" />
              SYNCHRONIZED
            </span>
          </div>
          <div className="text-[10px] text-slate-500 font-mono">
            Isolation: REPEATABLE READ
          </div>
        </div>
      </div>
    </aside>
  );
};
