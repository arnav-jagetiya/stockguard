import React, { useEffect, useState } from 'react';
import { getDashboardSummaryApi, getWarehouseDashboardApi } from '../../api/dashboard';
import { listWarehousesApi } from '../../api/warehouses';
import type { DashboardSummaryResponse, WarehouseDashboardResponse } from '../../types/dashboard';
import type { Warehouse as WarehouseType } from '../../types/warehouse';
import { StatCard } from '../../components/common/StatCard';
import { Card } from '../../components/common/Card';
import { Badge } from '../../components/common/Badge';
import { Skeleton } from '../../components/common/Skeleton';
import {
  Boxes,
  Package,
  Warehouse,
  ShieldAlert,
  ShoppingCart,
  BookmarkCheck,
  TrendingUp,
  Activity,
  Clock,
} from 'lucide-react';

export const DashboardPage: React.FC = () => {
  const [summary, setSummary] = useState<DashboardSummaryResponse | null>(null);
  const [warehouses, setWarehouses] = useState<WarehouseType[]>([]);
  const [selectedWarehouseId, setSelectedWarehouseId] = useState<string>('');
  const [warehouseDrilldown, setWarehouseDrilldown] = useState<WarehouseDashboardResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isDrilldownLoading, setIsDrilldownLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchDashboardData = async () => {
    setIsLoading(true);
    setError(null);
    try {
      const [sumRes, whList] = await Promise.all([
        getDashboardSummaryApi(),
        listWarehousesApi(undefined, true),
      ]);
      setSummary(sumRes);
      setWarehouses(whList);
      if (whList.length > 0 && !selectedWarehouseId) {
        setSelectedWarehouseId(whList[0].id);
      }
    } catch (err: unknown) {
      if (err instanceof Error) {
        setError(err.message);
      } else {
        setError('Failed to load operational dashboard summary.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchDashboardData();
  }, []);

  useEffect(() => {
    if (!selectedWarehouseId) return;
    const fetchDrilldown = async () => {
      setIsDrilldownLoading(true);
      try {
        const data = await getWarehouseDashboardApi(selectedWarehouseId);
        setWarehouseDrilldown(data);
      } catch {
        // Fallback silently if warehouse drilldown unavailable
      } finally {
        setIsDrilldownLoading(false);
      }
    };
    fetchDrilldown();
  }, [selectedWarehouseId]);

  if (isLoading) {
    return (
      <div className="space-y-8 animate-pulse">
        <div className="flex justify-between items-center">
          <div className="space-y-2">
            <Skeleton className="h-7 w-48" />
            <Skeleton className="h-4 w-72" />
          </div>
          <Skeleton className="h-4 w-32" />
        </div>
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
          {Array.from({ length: 4 }).map((_, i) => (
            <Skeleton key={i} className="h-32 rounded-xl" />
          ))}
        </div>
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <Skeleton className="lg:col-span-2 h-52 rounded-xl" />
          <Skeleton className="h-52 rounded-xl" />
        </div>
      </div>
    );
  }

  if (error || !summary) {
    return (
      <div className="p-6 rounded-xl bg-rose-50 dark:bg-rose-500/10 border border-rose-300 dark:border-rose-500/30 text-rose-700 dark:text-rose-300 text-sm space-y-2">
        <div className="font-bold flex items-center gap-2">
          <ShieldAlert className="w-4 h-4 text-rose-500 dark:text-rose-400" />
          Dashboard Data Unavailable
        </div>
        <p className="text-xs text-rose-600 dark:text-rose-400">{error || 'Unable to retrieve tenant metrics.'}</p>
        <button
          onClick={fetchDashboardData}
          className="text-xs text-cyan-600 dark:text-cyan-400 hover:underline pt-2 cursor-pointer font-medium"
        >
          Retry Connection
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-slate-900 dark:text-white tracking-tight">
            Operational Overview
          </h1>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
            Real-time multi-warehouse inventory health derived from PostgreSQL REPEATABLE READ snapshot.
          </p>
        </div>

        <div className="flex items-center gap-2 text-xs font-mono text-slate-500 dark:text-slate-400">
          <Clock className="w-3.5 h-3.5 text-cyan-600 dark:text-cyan-400" />
          <span>Snapshot: {new Date(summary.generatedAt).toLocaleTimeString()}</span>
        </div>
      </div>

      {/* Primary KPI Grid */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <StatCard
          title="Total On Hand"
          value={summary.inventory.totalOnHand.toLocaleString()}
          subtitle="Physical units across all sites"
          icon={<Boxes className="w-4 h-4" />}
          variant="cyan"
        />

        <StatCard
          title="Available to Promise"
          value={summary.inventory.totalAvailable.toLocaleString()}
          subtitle="Unreserved units ready for orders"
          icon={<TrendingUp className="w-4 h-4" />}
          variant="emerald"
        />

        <StatCard
          title="Active Reservations"
          value={summary.reservations.activeReservationsCount}
          subtitle={`${summary.reservations.totalReservedUnits} units locked`}
          icon={<BookmarkCheck className="w-4 h-4" />}
          variant="indigo"
        />

        <StatCard
          title="Open Discrepancies"
          value={summary.integrity.openDiscrepanciesCount}
          subtitle={
            summary.integrity.openDiscrepanciesCount === 0
              ? 'Zero inventory drift'
              : 'Requires operator audit'
          }
          icon={<ShieldAlert className="w-4 h-4" />}
          variant={summary.integrity.openDiscrepanciesCount === 0 ? 'emerald' : 'rose'}
        />
      </div>

      {/* Secondary Metrics: Orders & Catalog */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Order Pipeline Status Breakdown */}
        <Card className="lg:col-span-2 space-y-4">
          <div className="flex items-center justify-between">
            <div className="space-y-0.5">
              <h3 className="text-sm font-semibold text-slate-900 dark:text-white">Order Pipeline</h3>
              <p className="text-xs text-slate-500 dark:text-slate-400">Current lifecycle states of open customer orders</p>
            </div>
            <ShoppingCart className="w-4 h-4 text-cyan-600 dark:text-cyan-400" />
          </div>

          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 pt-2">
            <div className="p-3 rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 space-y-1">
              <span className="text-[10px] uppercase font-mono text-slate-500 font-semibold">Pending</span>
              <p className="text-lg font-bold text-amber-600 dark:text-amber-400 font-mono">{summary.orders.pendingCount}</p>
              <span className="text-[10px] text-slate-500">Unreserved</span>
            </div>

            <div className="p-3 rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 space-y-1">
              <span className="text-[10px] uppercase font-mono text-slate-500 font-semibold">Confirmed</span>
              <p className="text-lg font-bold text-cyan-600 dark:text-cyan-400 font-mono">{summary.orders.confirmedCount}</p>
              <span className="text-[10px] text-slate-500">Stock Allocated</span>
            </div>

            <div className="p-3 rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 space-y-1">
              <span className="text-[10px] uppercase font-mono text-slate-500 font-semibold">Fulfilled</span>
              <p className="text-lg font-bold text-emerald-600 dark:text-emerald-400 font-mono">{summary.orders.fulfilledCount}</p>
              <span className="text-[10px] text-slate-500">Dispatched</span>
            </div>

            <div className="p-3 rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 space-y-1">
              <span className="text-[10px] uppercase font-mono text-slate-500 font-semibold">Cancelled</span>
              <p className="text-lg font-bold text-slate-600 dark:text-slate-400 font-mono">{summary.orders.cancelledCount}</p>
              <span className="text-[10px] text-slate-500">Released</span>
            </div>
          </div>
        </Card>

        {/* Catalog Health & Stockouts */}
        <Card className="space-y-4">
          <div className="flex items-center justify-between">
            <div className="space-y-0.5">
              <h3 className="text-sm font-semibold text-slate-900 dark:text-white">Catalog & Stockouts</h3>
              <p className="text-xs text-slate-500 dark:text-slate-400">Active SKU distribution</p>
            </div>
            <Package className="w-4 h-4 text-indigo-600 dark:text-indigo-400" />
          </div>

          <div className="space-y-3 pt-2">
            <div className="flex items-center justify-between p-3 rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800">
              <span className="text-xs text-slate-600 dark:text-slate-400">Active Products</span>
              <span className="text-sm font-bold text-slate-900 dark:text-slate-100 font-mono">
                {summary.catalog.activeProductsCount}
              </span>
            </div>
            <div className="flex items-center justify-between p-3 rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800">
              <span className="text-xs text-slate-600 dark:text-slate-400">Active Warehouses</span>
              <span className="text-sm font-bold text-slate-900 dark:text-slate-100 font-mono">
                {summary.catalog.activeWarehousesCount}
              </span>
            </div>
            <div className="flex items-center justify-between p-3 rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800">
              <span className="text-xs text-slate-600 dark:text-slate-400">Out-of-Stock SKUs</span>
              <span className={`text-sm font-bold font-mono ${summary.inventory.outOfStockActiveProductsCount > 0 ? 'text-amber-600 dark:text-amber-400' : 'text-emerald-600 dark:text-emerald-400'}`}>
                {summary.inventory.outOfStockActiveProductsCount}
              </span>
            </div>
          </div>
        </Card>
      </div>

      {/* Warehouse Drilldown Selector */}
      {warehouses.length > 0 && (
        <Card className="space-y-4">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-3 border-b border-slate-200 dark:border-slate-800/80">
            <div>
              <div className="flex items-center gap-2">
                <Warehouse className="w-4 h-4 text-cyan-600 dark:text-cyan-400" />
                <h3 className="text-sm font-bold text-slate-900 dark:text-white">Facility Drilldown</h3>
              </div>
              <p className="text-xs text-slate-500 dark:text-slate-400">
                Inspect isolated balance and reservation breakdown by warehouse
              </p>
            </div>

            {/* Warehouse Selector */}
            <div className="flex items-center gap-2">
              <span className="text-xs text-slate-500 dark:text-slate-400 font-medium">Select Facility:</span>
              <select
                value={selectedWarehouseId}
                onChange={(e) => setSelectedWarehouseId(e.target.value)}
                className="bg-white dark:bg-slate-900 text-xs text-slate-800 dark:text-slate-200 border border-slate-300 dark:border-slate-800 rounded-lg px-3 py-1.5 outline-none cursor-pointer focus:border-cyan-500 shadow-xs"
              >
                {warehouses.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.code} — {w.name} (Priority {w.priority})
                  </option>
                ))}
              </select>
            </div>
          </div>

          {isDrilldownLoading ? (
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 pt-2">
              {Array.from({ length: 4 }).map((_, i) => (
                <Skeleton key={i} className="h-16 rounded-xl" />
              ))}
            </div>
          ) : warehouseDrilldown ? (
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 pt-2">
              <div className="p-3 rounded-xl bg-slate-50 dark:bg-slate-900/60 border border-slate-200 dark:border-slate-800">
                <span className="text-[10px] text-slate-500 dark:text-slate-400 uppercase font-mono">On Hand</span>
                <p className="text-base font-bold text-slate-900 dark:text-white font-mono mt-1">
                  {warehouseDrilldown.inventory.totalOnHand.toLocaleString()}
                </p>
              </div>
              <div className="p-3 rounded-xl bg-slate-50 dark:bg-slate-900/60 border border-slate-200 dark:border-slate-800">
                <span className="text-[10px] text-slate-500 dark:text-slate-400 uppercase font-mono">Available</span>
                <p className="text-base font-bold text-emerald-600 dark:text-emerald-400 font-mono mt-1">
                  {warehouseDrilldown.inventory.totalAvailable.toLocaleString()}
                </p>
              </div>
              <div className="p-3 rounded-xl bg-slate-50 dark:bg-slate-900/60 border border-slate-200 dark:border-slate-800">
                <span className="text-[10px] text-slate-500 dark:text-slate-400 uppercase font-mono">Allocated Units</span>
                <p className="text-base font-bold text-indigo-600 dark:text-indigo-400 font-mono mt-1">
                  {warehouseDrilldown.reservations.totalAllocatedUnits.toLocaleString()}
                </p>
              </div>
              <div className="p-3 rounded-xl bg-slate-50 dark:bg-slate-900/60 border border-slate-200 dark:border-slate-800">
                <span className="text-[10px] text-slate-500 dark:text-slate-400 uppercase font-mono">Out-of-Stock SKUs</span>
                <p className={`text-base font-bold font-mono mt-1 ${warehouseDrilldown.inventory.outOfStockProductsCount > 0 ? 'text-amber-600 dark:text-amber-400' : 'text-slate-500 dark:text-slate-400'}`}>
                  {warehouseDrilldown.inventory.outOfStockProductsCount}
                </p>
              </div>
            </div>
          ) : null}
        </Card>
      )}

      {/* Recent Movements Stream */}
      <Card className="space-y-4">
        <div className="flex items-center justify-between pb-3 border-b border-slate-200 dark:border-slate-800/80">
          <div>
            <h3 className="text-sm font-semibold text-slate-900 dark:text-white">Recent Ledger Movements</h3>
            <p className="text-xs text-slate-500 dark:text-slate-400">Auditable append-only double-entry transactions</p>
          </div>
          <Activity className="w-4 h-4 text-cyan-600 dark:text-cyan-400" />
        </div>

        {summary.recentMovements.length === 0 ? (
          <p className="text-xs text-slate-500 py-4 text-center">No recent stock movements recorded.</p>
        ) : (
          <div className="divide-y divide-slate-100 dark:divide-slate-800/60 font-mono text-xs">
            {summary.recentMovements.map((m) => (
              <div key={m.transactionId} className="py-2.5 flex items-center justify-between gap-4">
                <div className="flex items-center gap-3">
                  <Badge variant="info">{m.transactionType}</Badge>
                  <span className="text-slate-700 dark:text-slate-300 truncate max-w-xs">
                    {m.referenceType ? `${m.referenceType}: ${m.referenceId || 'N/A'}` : 'Direct Transaction'}
                  </span>
                </div>
                <div className="flex items-center gap-4 text-slate-500 dark:text-slate-400 shrink-0">
                  <span className="text-slate-600 dark:text-slate-500">{m.createdByName}</span>
                  <span className="text-[11px]">{new Date(m.createdAt).toLocaleTimeString()}</span>
                </div>
              </div>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
};
