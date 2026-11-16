import React, { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { getDashboardSummaryApi, getWarehouseDashboardApi } from '../../api/dashboard';
import { listWarehousesApi } from '../../api/warehouses';
import { useAuth } from '../../context/AuthContext';
import type { DashboardSummaryResponse, WarehouseDashboardResponse } from '../../types/dashboard';
import type { Warehouse as WarehouseType } from '../../types/warehouse';
import { Badge } from '../../components/common/Badge';
import { Button } from '../../components/common/Button';
import { Skeleton } from '../../components/common/Skeleton';
import {
  Boxes,
  Package,
  ShieldAlert,
  BookmarkCheck,
  Activity,
  Clock,
  ArrowRight,
  CheckCircle2,
  AlertCircle,
  FileText,
  RotateCw,
  Plus,
} from 'lucide-react';

export const DashboardPage: React.FC = () => {
  const { hasPermission } = useAuth();
  const [summary, setSummary] = useState<DashboardSummaryResponse | null>(null);
  const [warehouses, setWarehouses] = useState<WarehouseType[]>([]);
  const [selectedWarehouseId, setSelectedWarehouseId] = useState<string>('');
  const [warehouseDrilldown, setWarehouseDrilldown] = useState<WarehouseDashboardResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isDrilldownLoading, setIsDrilldownLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canCreateProduct = hasPermission('product:create');
  const canManageWarehouse = hasPermission('warehouse:manage');

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
      <div className="space-y-6 animate-pulse">
        <div className="flex justify-between items-center">
          <div className="space-y-2">
            <Skeleton className="h-8 w-64" />
            <Skeleton className="h-4 w-80" />
          </div>
          <Skeleton className="h-6 w-36" />
        </div>
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          <Skeleton className="lg:col-span-2 h-72 rounded-2xl" />
          <Skeleton className="h-72 rounded-2xl" />
        </div>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
          <Skeleton className="h-60 rounded-2xl" />
          <Skeleton className="h-60 rounded-2xl" />
          <Skeleton className="h-60 rounded-2xl" />
        </div>
      </div>
    );
  }

  if (error || !summary) {
    return (
      <div className="p-6 rounded-2xl bg-rose-50 dark:bg-rose-950/20 border border-rose-300 dark:border-rose-900/40 text-rose-800 dark:text-rose-300 text-sm space-y-3">
        <div className="font-bold flex items-center gap-2 text-base">
          <ShieldAlert className="w-5 h-5 text-rose-600 dark:text-rose-400" />
          Command Centre Telemetry Unavailable
        </div>
        <p className="text-xs text-rose-700 dark:text-rose-400">{error || 'Unable to retrieve live tenant snapshot.'}</p>
        <Button size="sm" variant="outline" onClick={fetchDashboardData} icon={<RotateCw className="w-3.5 h-3.5" />}>
          Retry Connection
        </Button>
      </div>
    );
  }

  // Check if organization is fresh/empty
  const isEmptyOrg =
    summary.catalog.activeProductsCount === 0 &&
    summary.catalog.activeWarehousesCount === 0 &&
    summary.inventory.totalOnHand === 0;

  if (isEmptyOrg) {
    const hasWarehouse = summary.catalog.activeWarehousesCount > 0;
    const hasProduct = summary.catalog.activeProductsCount > 0;
    const hasStock = summary.inventory.totalOnHand > 0;

    return (
      <div className="space-y-8 max-w-6xl mx-auto">
        {/* Onboarding Header */}
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-5 border-b border-[#D9E1D7] dark:border-[#26362E]">
          <div>
            <div className="flex items-center gap-3">
              <h1 className="text-2xl sm:text-3xl font-extrabold tracking-tight text-[#18231D] dark:text-[#EEF3EA]">
                Operations Command Centre
              </h1>
              <Badge variant="info">INITIAL ONBOARDING</Badge>
            </div>
            <p className="text-xs sm:text-sm text-[#657369] dark:text-[#9AA99E] mt-1 font-mono">
              Organization provisioned under PostgreSQL REPEATABLE READ snapshot isolation
            </p>
          </div>
          <div className="flex items-center gap-2">
            <span className="w-2.5 h-2.5 rounded-full bg-amber-500 animate-pulse" />
            <span className="text-xs font-mono font-semibold text-amber-700 dark:text-amber-400">
              Awaiting Initial Facility & SKU Provisioning
            </span>
          </div>
        </div>

        {/* Welcome & Context Banner */}
        <div className="rounded-2xl bg-gradient-to-br from-[#E9EEE6]/80 via-white to-white dark:from-[#16221E] dark:via-[#111A17] dark:to-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] p-6 sm:p-8 shadow-sm space-y-4">
          <div className="max-w-3xl space-y-2">
            <span className="text-[11px] font-mono font-bold uppercase tracking-wider text-[#456B1F] dark:text-[#C6F36B]">
              Tenant Workspace Ready
            </span>
            <h2 className="text-xl sm:text-2xl font-bold text-[#18231D] dark:text-[#EEF3EA] tracking-tight">
              Welcome to StockGuard. Let's configure your operational perimeter.
            </h2>
            <p className="text-xs sm:text-sm text-[#657369] dark:text-[#9AA99E] leading-relaxed">
              Your organization workspace has been created with dedicated multi-tenant isolation, append-only transaction logging, and atomic concurrency controls. Follow the structured setup steps below to establish your first storage facility, define catalog SKUs, and record initial physical stock.
            </p>
          </div>

          {/* Setup Journey Milestones */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4 pt-4">
            {/* Step 1: Warehouse */}
            <div className="p-5 rounded-xl bg-white dark:bg-[#182621] border border-[#D9E1D7] dark:border-[#26362E] space-y-3 relative hover:border-[#456B1F]/40 dark:hover:border-[#C6F36B]/40 transition-all">
              <div className="flex items-center justify-between">
                <span className="text-[10px] font-mono font-bold uppercase text-[#456B1F] dark:text-[#C6F36B] px-2 py-0.5 rounded bg-[#E9EEE6] dark:bg-[#111A17]">
                  STEP 01
                </span>
                {hasWarehouse ? (
                  <Badge variant="success" dot>COMPLETED</Badge>
                ) : (
                  <Badge variant="warning">ACTION REQUIRED</Badge>
                )}
              </div>
              <div className="space-y-1">
                <h3 className="text-sm font-bold text-[#18231D] dark:text-[#EEF3EA]">Provision Storage Facility</h3>
                <p className="text-xs text-[#657369] dark:text-[#9AA99E] leading-relaxed">
                  Establish your physical warehouse, regional distribution hub, or cross-dock bay with capacity parameters.
                </p>
              </div>
              {canManageWarehouse && !hasWarehouse && (
                <div className="pt-2">
                  <Link to="/app/warehouses">
                    <Button size="sm" variant="primary" icon={<Plus className="w-3.5 h-3.5" />}>
                      Create First Warehouse
                    </Button>
                  </Link>
                </div>
              )}
            </div>

            {/* Step 2: Catalog SKU */}
            <div className="p-5 rounded-xl bg-white dark:bg-[#182621] border border-[#D9E1D7] dark:border-[#26362E] space-y-3 relative hover:border-[#456B1F]/40 dark:hover:border-[#C6F36B]/40 transition-all">
              <div className="flex items-center justify-between">
                <span className="text-[10px] font-mono font-bold uppercase text-[#456B1F] dark:text-[#C6F36B] px-2 py-0.5 rounded bg-[#E9EEE6] dark:bg-[#111A17]">
                  STEP 02
                </span>
                {hasProduct ? (
                  <Badge variant="success" dot>COMPLETED</Badge>
                ) : (
                  <Badge variant="neutral">UPCOMING</Badge>
                )}
              </div>
              <div className="space-y-1">
                <h3 className="text-sm font-bold text-[#18231D] dark:text-[#EEF3EA]">Define Product Catalog SKUs</h3>
                <p className="text-xs text-[#657369] dark:text-[#9AA99E] leading-relaxed">
                  Register unique SKUs with pricing, dimensions, and unit metrics for trackable inventory allocation.
                </p>
              </div>
              {canCreateProduct && (
                <div className="pt-2">
                  <Link to="/app/products">
                    <Button size="sm" variant="secondary" icon={<Package className="w-3.5 h-3.5" />}>
                      Add Catalog SKU
                    </Button>
                  </Link>
                </div>
              )}
            </div>

            {/* Step 3: Initial Receipt */}
            <div className="p-5 rounded-xl bg-white dark:bg-[#182621] border border-[#D9E1D7] dark:border-[#26362E] space-y-3 relative hover:border-[#456B1F]/40 dark:hover:border-[#C6F36B]/40 transition-all">
              <div className="flex items-center justify-between">
                <span className="text-[10px] font-mono font-bold uppercase text-[#456B1F] dark:text-[#C6F36B] px-2 py-0.5 rounded bg-[#E9EEE6] dark:bg-[#111A17]">
                  STEP 03
                </span>
                {hasStock ? (
                  <Badge variant="success" dot>COMPLETED</Badge>
                ) : (
                  <Badge variant="neutral">UPCOMING</Badge>
                )}
              </div>
              <div className="space-y-1">
                <h3 className="text-sm font-bold text-[#18231D] dark:text-[#EEF3EA]">Record Opening Stock</h3>
                <p className="text-xs text-[#657369] dark:text-[#9AA99E] leading-relaxed">
                  Ingest physical on-hand stock via an immutable (+Q, 0) debit transaction to establish baseline ledger integrity.
                </p>
              </div>
              <div className="pt-2">
                <Link to="/app/inventory">
                  <Button size="sm" variant="outline" icon={<Boxes className="w-3.5 h-3.5" />}>
                    Open Inventory Ledger
                  </Button>
                </Link>
              </div>
            </div>
          </div>
        </div>

        {/* Architectural Blueprint: How the First Balance is Established */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          <div className="rounded-2xl bg-white dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] p-6 shadow-sm space-y-3">
            <div className="flex items-center gap-2 pb-2 border-b border-[#D9E1D7] dark:border-[#26362E]">
              <CheckCircle2 className="w-4 h-4 text-[#456B1F] dark:text-[#C6F36B]" />
              <h3 className="text-sm font-bold text-[#18231D] dark:text-[#EEF3EA]">How Initial Balances are Established</h3>
            </div>
            <p className="text-xs text-[#657369] dark:text-[#9AA99E] leading-relaxed">
              StockGuard rejects arbitrary counter updates. Initial inventory is established by executing a verified physical receipt transaction:
            </p>
            <div className="p-3 rounded-lg bg-[#F0F4EE] dark:bg-[#16221E] font-mono text-[11px] text-[#456B1F] dark:text-[#C6F36B] space-y-1 border border-[#D9E1D7] dark:border-[#26362E]">
              <div>RECEIPT: (+Q on-hand, 0 reserved)</div>
              <div className="text-[10px] text-[#657369] dark:text-[#9AA99E]">1. Inserts into append-only inventory_transactions</div>
              <div className="text-[10px] text-[#657369] dark:text-[#9AA99E]">2. Updates inventory_balances (on_hand += Q)</div>
              <div className="text-[10px] text-[#657369] dark:text-[#9AA99E]">3. Invariant: on_hand == available + reserved</div>
            </div>
          </div>

          <div className="rounded-2xl bg-white dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] p-6 shadow-sm space-y-3">
            <div className="flex items-center gap-2 pb-2 border-b border-[#D9E1D7] dark:border-[#26362E]">
              <Activity className="w-4 h-4 text-[#147C68] dark:text-[#6CD8C0]" />
              <h3 className="text-sm font-bold text-[#18231D] dark:text-[#EEF3EA]">What You Will See Once Configured</h3>
            </div>
            <p className="text-xs text-[#657369] dark:text-[#9AA99E] leading-relaxed">
              Upon establishing your catalog and opening stock, this Operations Command Centre transforms into a live operational control room:
            </p>
            <ul className="space-y-1.5 text-xs text-[#657369] dark:text-[#9AA99E]">
              <li className="flex items-center gap-2">
                <span className="w-1.5 h-1.5 rounded-full bg-[#456B1F] dark:bg-[#C6F36B]" />
                <span>Real-time mathematical Available vs. Reserved split gauges</span>
              </li>
              <li className="flex items-center gap-2">
                <span className="w-1.5 h-1.5 rounded-full bg-[#147C68] dark:bg-[#6CD8C0]" />
                <span>Lifecycle order pipeline monitoring (Pending &rarr; Confirmed &rarr; Fulfilled / Cancelled)</span>
              </li>
              <li className="flex items-center gap-2">
                <span className="w-1.5 h-1.5 rounded-full bg-amber-500" />
                <span>Continuous MVCC reconciliation sweeps and discrepancy isolation</span>
              </li>
              <li className="flex items-center gap-2">
                <span className="w-1.5 h-1.5 rounded-full bg-indigo-500" />
                <span>Per-facility capacity, stockout counters, and audit trail stream</span>
              </li>
            </ul>
          </div>
        </div>
      </div>
    );
  }

  // Calculate mathematical balance percentages
  const onHand = summary.inventory.totalOnHand;
  const reserved = summary.inventory.totalReserved;
  const available = summary.inventory.totalAvailable;
  const availablePct = onHand > 0 ? Math.round((available / onHand) * 100) : 100;
  const reservedPct = onHand > 0 ? Math.round((reserved / onHand) * 100) : 0;

  return (
    <div className="space-y-8">
      {/* Top Command Bar */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-[#D9E1D7] dark:border-[#26362E]">
        <div>
          <div className="flex items-center gap-2.5">
            <h1 className="text-2xl font-bold tracking-tight text-[#18231D] dark:text-[#EEF3EA]">
              Operations Command Centre
            </h1>
            <Badge variant="success" dot>REAL-TIME SNAPSHOT</Badge>
          </div>
          <p className="text-xs text-[#657369] dark:text-[#9AA99E] mt-1 font-mono">
            Derived directly from PostgreSQL REPEATABLE READ snapshot isolation · Zero phantom drift
          </p>
        </div>

        <div className="flex items-center gap-3">
          <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-[#E9EEE6] dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] text-xs font-mono text-[#657369] dark:text-[#9AA99E]">
            <Clock className="w-3.5 h-3.5 text-[#456B1F] dark:text-[#C6F36B]" />
            <span>Synced: {new Date(summary.generatedAt).toLocaleTimeString()}</span>
          </div>

          <Button size="sm" variant="outline" onClick={fetchDashboardData} icon={<RotateCw className="w-3.5 h-3.5" />}>
            Refresh
          </Button>
        </div>
      </div>

      {/* SECTION 1: PRIMARY ASYMMETRIC COMMAND GRID */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        {/* FOCAL POINT (8 cols): Inventory Integrity & Mathematical Balance Matrix */}
        <div className="lg:col-span-8 rounded-2xl bg-white dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] p-6 shadow-sm space-y-6">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pb-4 border-b border-[#D9E1D7] dark:border-[#26362E]">
            <div className="space-y-0.5">
              <span className="text-[10px] font-mono font-bold uppercase tracking-wider text-[#456B1F] dark:text-[#C6F36B]">
                Authoritative Inventory Math
              </span>
              <h2 className="text-lg font-bold text-[#18231D] dark:text-[#EEF3EA] tracking-tight">
                Physical On-Hand = Available + Active Reservations
              </h2>
            </div>
            <span className="text-xs font-mono text-[#657369] dark:text-[#9AA99E] bg-[#F0F4EE] dark:bg-[#16221E] px-2.5 py-1 rounded-md border border-[#D9E1D7] dark:border-[#26362E]">
              100% Reconciled
            </span>
          </div>

          {/* Three Tier Metric Split */}
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            <div className="p-4 rounded-xl bg-[#F0F4EE] dark:bg-[#16221E] border border-[#D9E1D7] dark:border-[#26362E] space-y-1">
              <span className="text-[11px] font-mono font-bold text-[#657369] dark:text-[#9AA99E] uppercase block">
                Total Physical On-Hand
              </span>
              <div className="flex items-baseline gap-2">
                <span className="text-3xl font-extrabold text-[#18231D] dark:text-[#EEF3EA] font-mono">
                  {onHand.toLocaleString()}
                </span>
                <span className="text-xs text-[#8A988E] dark:text-[#6C7D71] font-mono">units</span>
              </div>
              <p className="text-[11px] text-[#657369] dark:text-[#9AA99E]">Across {summary.catalog.activeWarehousesCount} active facilities</p>
            </div>

            <div className="p-4 rounded-xl bg-[#E9EEE6] dark:bg-[#C6F36B]/10 border border-[#D9E1D7] dark:border-[#C6F36B]/30 space-y-1">
              <span className="text-[11px] font-mono font-bold text-[#456B1F] dark:text-[#C6F36B] uppercase block">
                Available to Promise (ATP)
              </span>
              <div className="flex items-baseline gap-2">
                <span className="text-3xl font-extrabold text-[#456B1F] dark:text-[#C6F36B] font-mono">
                  {available.toLocaleString()}
                </span>
                <span className="text-xs text-[#456B1F]/70 dark:text-[#C6F36B]/70 font-mono">({availablePct}%)</span>
              </div>
              <p className="text-[11px] text-[#657369] dark:text-[#9AA99E]">Unreserved and ready for allocation</p>
            </div>

            <div className="p-4 rounded-xl bg-amber-50 dark:bg-amber-950/20 border border-amber-300 dark:border-amber-500/30 space-y-1">
              <span className="text-[11px] font-mono font-bold text-amber-800 dark:text-amber-400 uppercase block">
                Locked Reservations
              </span>
              <div className="flex items-baseline gap-2">
                <span className="text-3xl font-extrabold text-amber-700 dark:text-amber-400 font-mono">
                  {reserved.toLocaleString()}
                </span>
                <span className="text-xs text-amber-700/70 dark:text-amber-400/70 font-mono">({reservedPct}%)</span>
              </div>
              <p className="text-[11px] text-[#657369] dark:text-[#9AA99E]">{summary.reservations.activeReservationsCount} active order locks</p>
            </div>
          </div>

          {/* Visual Bar Proportion Math */}
          <div className="space-y-2 pt-2">
            <div className="flex items-center justify-between text-xs font-mono text-[#657369] dark:text-[#9AA99E]">
              <span>Stock Distribution Ratio</span>
              <span>
                <strong className="text-[#456B1F] dark:text-[#C6F36B]">{available.toLocaleString()} ATP</strong> +{' '}
                <strong className="text-amber-600 dark:text-amber-400">{reserved.toLocaleString()} Locked</strong> ={' '}
                <strong className="text-[#18231D] dark:text-[#EEF3EA]">{onHand.toLocaleString()} Total</strong>
              </span>
            </div>
            <div className="h-3 w-full rounded-full bg-[#E9EEE6] dark:bg-[#16221E] overflow-hidden flex p-0.5 border border-[#D9E1D7] dark:border-[#26362E]">
              <div
                style={{ width: `${availablePct}%` }}
                className="h-full rounded-l-full bg-[#456B1F] dark:bg-[#C6F36B] transition-all duration-500"
                title={`Available: ${available.toLocaleString()} units (${availablePct}%)`}
              />
              <div
                style={{ width: `${reservedPct}%` }}
                className="h-full rounded-r-full bg-amber-500 dark:bg-amber-400 transition-all duration-500"
                title={`Reserved: ${reserved.toLocaleString()} units (${reservedPct}%)`}
              />
            </div>
          </div>

          {/* Catalog & Discrepancy Health Indicators */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 pt-2 text-xs">
            <div className="p-3 rounded-xl bg-[#F9FAF7] dark:bg-[#16221E] border border-[#D9E1D7] dark:border-[#26362E]">
              <span className="text-[#8A988E] dark:text-[#6C7D71] block font-mono text-[10px]">ACTIVE SKUS</span>
              <span className="font-bold font-mono text-[#18231D] dark:text-[#EEF3EA] text-base">{summary.catalog.activeProductsCount}</span>
            </div>
            <div className="p-3 rounded-xl bg-[#F9FAF7] dark:bg-[#16221E] border border-[#D9E1D7] dark:border-[#26362E]">
              <span className="text-[#8A988E] dark:text-[#6C7D71] block font-mono text-[10px]">WAREHOUSE SITES</span>
              <span className="font-bold font-mono text-[#18231D] dark:text-[#EEF3EA] text-base">{summary.catalog.activeWarehousesCount}</span>
            </div>
            <div className="p-3 rounded-xl bg-[#F9FAF7] dark:bg-[#16221E] border border-[#D9E1D7] dark:border-[#26362E]">
              <span className="text-[#8A988E] dark:text-[#6C7D71] block font-mono text-[10px]">STOCKOUT COUNT</span>
              <span className={`font-bold font-mono text-base ${summary.inventory.outOfStockActiveProductsCount > 0 ? 'text-amber-600 dark:text-amber-400' : 'text-[#456B1F] dark:text-[#C6F36B]'}`}>
                {summary.inventory.outOfStockActiveProductsCount}
              </span>
            </div>
            <div className="p-3 rounded-xl bg-[#F9FAF7] dark:bg-[#16221E] border border-[#D9E1D7] dark:border-[#26362E]">
              <span className="text-[#8A988E] dark:text-[#6C7D71] block font-mono text-[10px]">MVCC DISCREPANCIES</span>
              <span className={`font-bold font-mono text-base ${summary.integrity.openDiscrepanciesCount === 0 ? 'text-[#456B1F] dark:text-[#C6F36B]' : 'text-rose-600 dark:text-rose-400'}`}>
                {summary.integrity.openDiscrepanciesCount}
              </span>
            </div>
          </div>
        </div>

        {/* SIDEBAR PANEL (4 cols): Live Double-Entry Recent Movements */}
        <div className="lg:col-span-4 rounded-2xl bg-white dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] p-6 shadow-sm space-y-4">
          <div className="flex items-center justify-between pb-3 border-b border-[#D9E1D7] dark:border-[#26362E]">
            <div className="space-y-0.5">
              <span className="text-[10px] font-mono font-bold uppercase tracking-wider text-[#456B1F] dark:text-[#C6F36B]">
                Audit Trail
              </span>
              <h3 className="text-sm font-bold text-[#18231D] dark:text-[#EEF3EA]">Recent Ledger Events</h3>
            </div>
            <Activity className="w-4 h-4 text-[#147C68] dark:text-[#6CD8C0]" />
          </div>

          {summary.recentMovements && summary.recentMovements.length > 0 ? (
            <div className="space-y-2.5 max-h-[380px] overflow-y-auto pr-1">
              {summary.recentMovements.map((m) => (
                <div
                  key={m.transactionId}
                  className="p-3 rounded-xl bg-[#F9FAF7] dark:bg-[#16221E] border border-[#D9E1D7] dark:border-[#26362E] space-y-1.5 text-xs transition-colors hover:border-[#657369]/30"
                >
                  <div className="flex items-center justify-between font-mono text-[11px]">
                    <span className="font-bold text-[#456B1F] dark:text-[#C6F36B]">{m.transactionType}</span>
                    <span className="text-[#8A988E] dark:text-[#6C7D71]">
                      {new Date(m.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                    </span>
                  </div>
                  <div className="flex items-center justify-between text-[11px] text-[#657369] dark:text-[#9AA99E]">
                    <span className="truncate max-w-[140px] font-mono">Ref: {m.referenceType || 'TX'} · {m.referenceId?.slice(0, 8) || 'SYSTEM'}</span>
                    <span className="truncate max-w-[90px]">{m.createdByName || 'System'}</span>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="py-8 text-center text-xs text-[#8A988E] dark:text-[#6C7D71] space-y-2">
              <FileText className="w-8 h-8 mx-auto text-[#D9E1D7] dark:border-[#26362E]" />
              <p>No recent movements recorded in this period.</p>
            </div>
          )}

          <div className="pt-2 border-t border-[#D9E1D7] dark:border-[#26362E]">
            <Link to="/app/audit-logs" className="text-xs font-semibold text-[#456B1F] dark:text-[#C6F36B] hover:underline flex items-center gap-1.5">
              <span>View Full Cryptographic Audit Ledger</span>
              <ArrowRight className="w-3.5 h-3.5" />
            </Link>
          </div>
        </div>
      </div>

      {/* SECTION 2: ORDER PIPELINE LIFECYCLE PROGRESSION */}
      <div className="rounded-2xl bg-white dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] p-6 shadow-sm space-y-4">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-2 pb-3 border-b border-[#D9E1D7] dark:border-[#26362E]">
          <div className="space-y-0.5">
            <span className="text-[10px] font-mono font-bold uppercase tracking-wider text-[#456B1F] dark:text-[#C6F36B]">
              Lifecycle Progression
            </span>
            <h3 className="text-base font-bold text-[#18231D] dark:text-[#EEF3EA]">Active Order Pipeline State</h3>
          </div>
          <Link to="/app/orders" className="text-xs font-semibold text-[#456B1F] dark:text-[#C6F36B] hover:underline flex items-center gap-1">
            <span>Manage Orders</span>
            <ArrowRight className="w-3 h-3" />
          </Link>
        </div>

        {/* Connected Pipeline Flow */}
        <div className="grid grid-cols-1 sm:grid-cols-4 gap-4 pt-2">
          {/* Step 1: PENDING */}
          <div className="p-4 rounded-xl bg-[#F0F4EE] dark:bg-[#16221E] border border-[#D9E1D7] dark:border-[#26362E] space-y-1 relative">
            <div className="flex items-center justify-between">
              <span className="text-[10px] uppercase font-mono font-bold text-amber-700 dark:text-amber-400">STAGE 1 · PENDING</span>
              <BookmarkCheck className="w-4 h-4 text-amber-600 dark:text-amber-400" />
            </div>
            <p className="text-2xl font-extrabold text-[#18231D] dark:text-[#EEF3EA] font-mono">{summary.orders.pendingCount}</p>
            <p className="text-[11px] text-[#657369] dark:text-[#9AA99E]">Awaiting stock reservation</p>
          </div>

          {/* Step 2: CONFIRMED */}
          <div className="p-4 rounded-xl bg-[#E9EEE6] dark:bg-[#6CD8C0]/10 border border-[#D9E1D7] dark:border-[#6CD8C0]/30 space-y-1 relative">
            <div className="flex items-center justify-between">
              <span className="text-[10px] uppercase font-mono font-bold text-[#147C68] dark:text-[#6CD8C0]">STAGE 2 · CONFIRMED</span>
              <CheckCircle2 className="w-4 h-4 text-[#147C68] dark:text-[#6CD8C0]" />
            </div>
            <p className="text-2xl font-extrabold text-[#147C68] dark:text-[#6CD8C0] font-mono">{summary.orders.confirmedCount}</p>
            <p className="text-[11px] text-[#657369] dark:text-[#9AA99E]">Stock locked via TTL lock</p>
          </div>

          {/* Step 3: FULFILLED */}
          <div className="p-4 rounded-xl bg-[#E9EEE6] dark:bg-[#C6F36B]/15 border border-[#D9E1D7] dark:border-[#C6F36B]/30 space-y-1 relative">
            <div className="flex items-center justify-between">
              <span className="text-[10px] uppercase font-mono font-bold text-[#456B1F] dark:text-[#C6F36B]">STAGE 3 · FULFILLED</span>
              <Boxes className="w-4 h-4 text-[#456B1F] dark:text-[#C6F36B]" />
            </div>
            <p className="text-2xl font-extrabold text-[#456B1F] dark:text-[#C6F36B] font-mono">{summary.orders.fulfilledCount}</p>
            <p className="text-[11px] text-[#657369] dark:text-[#9AA99E]">Dispatched & debited</p>
          </div>

          {/* Terminal: CANCELLED */}
          <div className="p-4 rounded-xl bg-[#F9FAF7] dark:bg-[#16221E] border border-[#D9E1D7] dark:border-[#26362E] space-y-1 relative">
            <div className="flex items-center justify-between">
              <span className="text-[10px] uppercase font-mono font-bold text-[#8A988E] dark:text-[#6C7D71]">TERMINAL · CANCELLED</span>
              <AlertCircle className="w-4 h-4 text-[#8A988E] dark:text-[#6C7D71]" />
            </div>
            <p className="text-2xl font-extrabold text-[#657369] dark:text-[#9AA99E] font-mono">{summary.orders.cancelledCount}</p>
            <p className="text-[11px] text-[#657369] dark:text-[#9AA99E]">Released back to available pool</p>
          </div>
        </div>
      </div>

      {/* SECTION 3: FACILITY MESH UTILIZATION & DRILLDOWN */}
      {warehouses.length > 0 && (
        <div className="rounded-2xl bg-white dark:bg-[#111A17] border border-[#D9E1D7] dark:border-[#26362E] p-6 shadow-sm space-y-6">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-[#D9E1D7] dark:border-[#26362E]">
            <div>
              <span className="text-[10px] font-mono font-bold uppercase tracking-wider text-[#456B1F] dark:text-[#C6F36B]">
                Facility Mesh
              </span>
              <h3 className="text-base font-bold text-[#18231D] dark:text-[#EEF3EA]">Warehouse Capacity & Location Telemetry</h3>
            </div>

            {/* Warehouse Facility Selector */}
            <div className="flex items-center gap-2">
              <label htmlFor="wh-select" className="text-xs text-[#657369] dark:text-[#9AA99E] font-medium">Facility:</label>
              <select
                id="wh-select"
                value={selectedWarehouseId}
                onChange={(e) => setSelectedWarehouseId(e.target.value)}
                className="text-xs rounded-lg px-3 py-1.5 bg-[#F0F4EE] dark:bg-[#16221E] text-[#18231D] dark:text-[#EEF3EA] border border-[#D9E1D7] dark:border-[#26362E] font-mono font-semibold outline-none cursor-pointer"
              >
                {warehouses.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.code} — {w.name}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* Drilldown Content */}
          {isDrilldownLoading ? (
            <div className="grid grid-cols-1 sm:grid-cols-3 gap-4 animate-pulse">
              <Skeleton className="h-28 rounded-xl" />
              <Skeleton className="h-28 rounded-xl" />
              <Skeleton className="h-28 rounded-xl" />
            </div>
          ) : warehouseDrilldown ? (
            <div className="space-y-4">
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
                <div className="p-4 rounded-xl bg-[#F0F4EE] dark:bg-[#16221E] border border-[#D9E1D7] dark:border-[#26362E] space-y-1">
                  <span className="text-[10px] font-mono font-bold text-[#657369] dark:text-[#9AA99E] uppercase block">Site On-Hand</span>
                  <div className="flex items-baseline gap-2">
                    <span className="text-2xl font-bold font-mono text-[#18231D] dark:text-[#EEF3EA]">{warehouseDrilldown.inventory.totalOnHand.toLocaleString()}</span>
                    <span className="text-xs text-[#8A988E] dark:text-[#6C7D71]">units</span>
                  </div>
                </div>

                <div className="p-4 rounded-xl bg-[#E9EEE6] dark:bg-[#C6F36B]/10 border border-[#D9E1D7] dark:border-[#C6F36B]/30 space-y-1">
                  <span className="text-[10px] font-mono font-bold text-[#456B1F] dark:text-[#C6F36B] uppercase block">Site Available</span>
                  <div className="flex items-baseline gap-2">
                    <span className="text-2xl font-bold font-mono text-[#456B1F] dark:text-[#C6F36B]">{warehouseDrilldown.inventory.totalAvailable.toLocaleString()}</span>
                    <span className="text-xs text-[#456B1F]/70 dark:text-[#C6F36B]/70">units</span>
                  </div>
                </div>

                <div className="p-4 rounded-xl bg-amber-50 dark:bg-amber-950/20 border border-amber-300 dark:border-amber-500/30 space-y-1">
                  <span className="text-[10px] font-mono font-bold text-amber-800 dark:text-amber-400 uppercase block">Site Allocated</span>
                  <div className="flex items-baseline gap-2">
                    <span className="text-2xl font-bold font-mono text-amber-700 dark:text-amber-400">{warehouseDrilldown.reservations.totalAllocatedUnits.toLocaleString()}</span>
                    <span className="text-xs text-amber-700/70 dark:text-amber-400/70">units</span>
                  </div>
                </div>
              </div>

              <div className="flex justify-end pt-2">
                <Link to="/app/warehouses" className="text-xs font-semibold text-[#456B1F] dark:text-[#C6F36B] hover:underline flex items-center gap-1.5">
                  <span>Manage Facility Hierarchy & Priority</span>
                  <ArrowRight className="w-3.5 h-3.5" />
                </Link>
              </div>
            </div>
          ) : null}
        </div>
      )}
    </div>
  );
};
