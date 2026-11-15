import React, { useEffect, useState } from 'react';
import { useAuth } from '../../context/AuthContext';
import {
  triggerReconciliationApi,
  listDiscrepanciesApi,
  updateDiscrepancyApi,
} from '../../api/reconciliation';
import { listProductsApi } from '../../api/products';
import { listWarehousesApi } from '../../api/warehouses';
import { getMembershipsApi } from '../../api/organizations';
import type {
  Discrepancy,
  DiscrepancyStatus,
  ReconciliationReport,
} from '../../types/discrepancy';
import type { Product } from '../../types/product';
import type { Warehouse as WarehouseType } from '../../types/warehouse';
import type { OrganizationMember } from '../../types/organization';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { Select } from '../../components/common/Select';
import { Modal } from '../../components/common/Modal';
import { Badge } from '../../components/common/Badge';
import { Table, TableHead, TableHeader, TableBody, TableRow, TableCell } from '../../components/common/Table';
import { LoadingSpinner } from '../../components/common/LoadingSpinner';
import { EmptyState } from '../../components/common/EmptyState';
import {
  ShieldCheck,
  RotateCw,
  Eye,
  CheckCircle2,
} from 'lucide-react';

export const ReconciliationPage: React.FC = () => {
  const { hasPermission } = useAuth();
  const canReconcile = hasPermission('inventory:reconcile');

  const [discrepancies, setDiscrepancies] = useState<Discrepancy[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [selectedStatus, setSelectedStatus] = useState<string>('OPEN');
  const [products, setProducts] = useState<Product[]>([]);
  const [warehouses, setWarehouses] = useState<WarehouseType[]>([]);
  const [members, setMembers] = useState<OrganizationMember[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isTriggering, setIsTriggering] = useState(false);
  const [triggerReport, setTriggerReport] = useState<ReconciliationReport | null>(null);

  // Detail & Triage Modal
  const [activeDiscrepancy, setActiveDiscrepancy] = useState<Discrepancy | null>(null);
  const [isTriageOpen, setIsTriageOpen] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [triageError, setTriageError] = useState<string | null>(null);

  // Triage form
  const [newStatus, setNewStatus] = useState<DiscrepancyStatus>('ACKNOWLEDGED');
  const [assignedUserId, setAssignedUserId] = useState<string>('');
  const [notes, setNotes] = useState('');
  const [reason, setReason] = useState('');

  const loadData = async () => {
    setIsLoading(true);
    try {
      const [discPage, prodList, whList, memList] = await Promise.all([
        listDiscrepanciesApi({
          status: (selectedStatus as DiscrepancyStatus) || undefined,
          limit: 50,
          offset: 0,
        }),
        listProductsApi(undefined, true),
        listWarehousesApi(undefined, true),
        getMembershipsApi().catch(() => []),
      ]);
      setDiscrepancies(discPage.items || discPage.content || []);
      setTotalElements(discPage.totalCount !== undefined ? discPage.totalCount : (discPage.totalElements || 0));
      setProducts(prodList);
      setWarehouses(whList);
      setMembers(memList);
    } catch (err: unknown) {
      if (err instanceof Error) alert(err.message);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [selectedStatus]);

  const handleTriggerSweep = async () => {
    setIsTriggering(true);
    try {
      const report = await triggerReconciliationApi();
      setTriggerReport(report);
      await loadData();
    } catch (err: unknown) {
      if (err instanceof Error) alert(`Sweep failed: ${err.message}`);
    } finally {
      setIsTriggering(false);
    }
  };

  const openTriage = (d: Discrepancy) => {
    setActiveDiscrepancy(d);
    setNewStatus(d.status === 'OPEN' ? 'ACKNOWLEDGED' : d.status);
    setAssignedUserId(d.assignedToUserId || '');
    setNotes(d.investigationNotes || '');
    setReason(d.resolutionReason || '');
    setTriageError(null);
    setIsTriageOpen(true);
  };

  const handleSaveTriage = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!activeDiscrepancy) return;

    if ((newStatus === 'RESOLVED' || newStatus === 'REJECTED') && !reason.trim()) {
      setTriageError('Resolution reason is mandatory when resolving or rejecting a discrepancy.');
      return;
    }

    setTriageError(null);
    setIsSubmitting(true);
    try {
      await updateDiscrepancyApi(activeDiscrepancy.id, {
        version: activeDiscrepancy.version,
        status: newStatus,
        assignedToUserId: assignedUserId || null,
        investigationNotes: notes.trim() || undefined,
        resolutionReason: reason.trim() || undefined,
      });
      setIsTriageOpen(false);
      await loadData();
    } catch (err: unknown) {
      if (err instanceof Error) setTriageError(err.message);
    } finally {
      setIsSubmitting(false);
    }
  };

  const getProductName = (id: string) => {
    const p = products.find((x) => x.id === id);
    return p ? `${p.sku} — ${p.name}` : id;
  };

  const getWarehouseName = (id: string) => {
    const w = warehouses.find((x) => x.id === id);
    return w ? w.code : id;
  };

  const getStatusBadge = (st: DiscrepancyStatus) => {
    switch (st) {
      case 'OPEN':
        return <Badge variant="error" dot>OPEN</Badge>;
      case 'ACKNOWLEDGED':
        return <Badge variant="warning">ACKNOWLEDGED</Badge>;
      case 'INVESTIGATING':
        return <Badge variant="accent">INVESTIGATING</Badge>;
      case 'RESOLVED':
        return <Badge variant="success">RESOLVED</Badge>;
      case 'REJECTED':
        return <Badge variant="neutral">REJECTED</Badge>;
      default:
        return <Badge>{st}</Badge>;
    }
  };

  const isTerminal = activeDiscrepancy?.status === 'RESOLVED' || activeDiscrepancy?.status === 'REJECTED';

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-white tracking-tight">
            Inventory Reconciliation Engine
          </h1>
          <p className="text-xs text-slate-400 mt-1">
            Automated MVCC snapshot verification, discrepancy persistence, and operator triage.
          </p>
        </div>

        {canReconcile && (
          <Button
            onClick={handleTriggerSweep}
            isLoading={isTriggering}
            icon={<RotateCw className="w-4 h-4" />}
          >
            Trigger Sweep Now
          </Button>
        )}
      </div>

      {/* Sweep Banner if triggered */}
      {triggerReport && (
        <div className="p-4 rounded-xl bg-cyan-500/10 border border-cyan-500/30 text-xs text-cyan-200 flex items-start justify-between gap-4 animate-in fade-in">
          <div className="space-y-1">
            <span className="font-bold flex items-center gap-1.5 text-cyan-300">
              <CheckCircle2 className="w-4 h-4 text-cyan-400" /> Reconciliation Sweep Complete
            </span>
            <p className="font-mono text-slate-300">
              Inspected: {triggerReport.totalBalancesInspected} balances | Matching: {triggerReport.matchingBalances} | Discrepant: {triggerReport.discrepantBalances}
            </p>
          </div>
          <button
            onClick={() => setTriggerReport(null)}
            className="text-slate-400 hover:text-white text-xs underline cursor-pointer"
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Filter */}
      <div className="flex items-center gap-4 bg-[#0f172a] p-3.5 rounded-xl border border-slate-800">
        <div className="w-64">
          <Select
            label="Filter by Status"
            value={selectedStatus}
            onChange={(e) => setSelectedStatus(e.target.value)}
            options={[
              { value: '', label: 'All Discrepancies' },
              { value: 'OPEN', label: 'Active (Open)' },
              { value: 'ACKNOWLEDGED', label: 'Acknowledged' },
              { value: 'INVESTIGATING', label: 'Investigating' },
              { value: 'RESOLVED', label: 'Resolved (Terminal)' },
              { value: 'REJECTED', label: 'Rejected (Terminal)' },
            ]}
          />
        </div>
        <div className="text-xs text-slate-400 font-mono pt-5">
          Showing {discrepancies.length} of {totalElements} record(s)
        </div>
      </div>

      {/* Discrepancy Table */}
      {isLoading ? (
        <LoadingSpinner message="Scanning discrepancy records..." />
      ) : discrepancies.length === 0 ? (
        <EmptyState
          icon={<ShieldCheck className="w-8 h-8 text-emerald-400" />}
          title="No Discrepancies Detected"
          description={selectedStatus ? `No discrepancies matching '${selectedStatus}' state.` : 'All inventory balances and double-entry ledger transactions are 100% synchronized.'}
          actionLabel={canReconcile ? 'Run Verification Sweep' : undefined}
          onAction={handleTriggerSweep}
        />
      ) : (
        <Table>
          <TableHead>
            <tr>
              <TableHeader>Product (SKU)</TableHeader>
              <TableHeader>Warehouse</TableHeader>
              <TableHeader>Type</TableHeader>
              <TableHeader>Balance vs Ledger</TableHeader>
              <TableHeader>Detections</TableHeader>
              <TableHeader>Status</TableHeader>
              <TableHeader>Last Detected</TableHeader>
              <TableHeader className="text-right">Action</TableHeader>
            </tr>
          </TableHead>
          <TableBody>
            {discrepancies.map((d) => {
              const delta = d.observedBalanceOnHand - d.observedLedgerOnHand;
              return (
                <TableRow key={d.id}>
                  <TableCell className="font-semibold text-white">
                    {getProductName(d.productId)}
                  </TableCell>
                  <TableCell className="font-mono text-cyan-400">
                    {getWarehouseName(d.warehouseId)}
                  </TableCell>
                  <TableCell>
                    <Badge variant="warning">{d.discrepancyType}</Badge>
                  </TableCell>
                  <TableCell className="font-mono text-xs">
                    <span className="text-slate-300">Bal: {d.observedBalanceOnHand}</span>{' '}
                    <span className="text-slate-500">|</span>{' '}
                    <span className="text-slate-300">Led: {d.observedLedgerOnHand}</span>{' '}
                    <span className={`font-bold ml-1 ${delta === 0 ? 'text-slate-400' : delta > 0 ? 'text-amber-400' : 'text-rose-400'}`}>
                      ({delta > 0 ? `+${delta}` : delta})
                    </span>
                  </TableCell>
                  <TableCell className="font-mono text-xs text-slate-400 font-bold">
                    {d.consecutiveDetectionCount}x
                  </TableCell>
                  <TableCell>{getStatusBadge(d.status)}</TableCell>
                  <TableCell className="font-mono text-xs text-slate-500">
                    {new Date(d.lastDetectedAt).toLocaleDateString()}
                  </TableCell>
                  <TableCell className="text-right">
                    <Button
                      size="sm"
                      variant={d.status === 'OPEN' ? 'primary' : 'outline'}
                      onClick={() => openTriage(d)}
                      icon={<Eye className="w-3.5 h-3.5" />}
                    >
                      {d.status === 'OPEN' ? 'Triage' : 'Inspect'}
                    </Button>
                  </TableCell>
                </TableRow>
              );
            })}
          </TableBody>
        </Table>
      )}

      {/* Triage / Resolution Modal */}
      <Modal
        isOpen={isTriageOpen}
        onClose={() => setIsTriageOpen(false)}
        title={isTerminal ? 'Discrepancy Inspection (Terminal)' : 'Discrepancy Triage & Resolution'}
        description={`Record ID: ${activeDiscrepancy?.id} · Version: ${activeDiscrepancy?.version}`}
        maxWidth="lg"
      >
        {activeDiscrepancy && (
          <form onSubmit={handleSaveTriage} className="space-y-4">
            {triageError && (
              <div className="p-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-rose-400 text-xs">
                {triageError}
              </div>
            )}

            {/* Metrics Breakdown */}
            <div className="grid grid-cols-2 gap-3 p-3 rounded-xl bg-slate-900 border border-slate-800 text-xs font-mono">
              <div>
                <span className="text-slate-500 block">Balance On-Hand:</span>
                <span className="font-bold text-white text-sm">{activeDiscrepancy.observedBalanceOnHand}</span>
              </div>
              <div>
                <span className="text-slate-500 block">Ledger On-Hand:</span>
                <span className="font-bold text-amber-400 text-sm">{activeDiscrepancy.observedLedgerOnHand}</span>
              </div>
              <div>
                <span className="text-slate-500 block">Balance Reserved:</span>
                <span className="font-bold text-white">{activeDiscrepancy.observedBalanceReserved}</span>
              </div>
              <div>
                <span className="text-slate-500 block">Ledger Reserved:</span>
                <span className="font-bold text-indigo-400">{activeDiscrepancy.observedLedgerReserved}</span>
              </div>
            </div>

            {/* State & Assignment Controls */}
            {!isTerminal ? (
              <>
                <div className="grid grid-cols-2 gap-3">
                  <Select
                    label="Transition Lifecycle Status"
                    value={newStatus}
                    onChange={(e) => setNewStatus(e.target.value as DiscrepancyStatus)}
                    options={[
                      { value: 'ACKNOWLEDGED', label: 'Acknowledge' },
                      { value: 'INVESTIGATING', label: 'Investigating' },
                      { value: 'RESOLVED', label: 'Resolve (Compensating Delta)' },
                      { value: 'REJECTED', label: 'Reject (Benign/False Alarm)' },
                    ]}
                  />

                  <Select
                    label="Assign Operator"
                    value={assignedUserId}
                    onChange={(e) => setAssignedUserId(e.target.value)}
                    options={[
                      { value: '', label: 'Unassigned' },
                      ...members.map((m) => ({ value: m.userId, label: `User: ${m.userId.substring(0, 8)}... (${m.role})` })),
                    ]}
                  />
                </div>

                <Input
                  label="Investigation Notes"
                  placeholder="Notes from warehouse physical count verification..."
                  value={notes}
                  onChange={(e) => setNotes(e.target.value)}
                />

                {(newStatus === 'RESOLVED' || newStatus === 'REJECTED') && (
                  <Input
                    label="Mandatory Resolution / Rejection Reason"
                    placeholder="e.g. Compensating cycle count adjustment performed"
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    required
                    helperText="Mandatory non-blank reason is enforced by PostgreSQL check constraint."
                  />
                )}

                <div className="flex justify-end gap-2 pt-3 border-t border-slate-800">
                  <Button type="button" variant="ghost" onClick={() => setIsTriageOpen(false)}>
                    Cancel
                  </Button>
                  <Button type="submit" isLoading={isSubmitting}>
                    Save Triage Decision
                  </Button>
                </div>
              </>
            ) : (
              /* Read-only Terminal State */
              <div className="space-y-3 pt-2">
                <div className="p-3 rounded-xl bg-slate-900 border border-slate-800 text-xs space-y-1.5">
                  <div className="flex items-center justify-between">
                    <span className="text-slate-400">Terminal Status:</span>
                    {getStatusBadge(activeDiscrepancy.status)}
                  </div>
                  <div className="flex items-center justify-between font-mono">
                    <span className="text-slate-400">Resolution Source:</span>
                    <span className="text-cyan-400">{activeDiscrepancy.resolutionSource || 'OPERATOR_OVERRIDE'}</span>
                  </div>
                  <div className="flex items-center justify-between font-mono">
                    <span className="text-slate-400">Resolved At:</span>
                    <span className="text-slate-300">
                      {activeDiscrepancy.resolvedAt ? new Date(activeDiscrepancy.resolvedAt).toLocaleString() : 'N/A'}
                    </span>
                  </div>
                  {activeDiscrepancy.resolutionReason && (
                    <div className="pt-2 border-t border-slate-800">
                      <span className="text-slate-400 block text-[11px]">Reason:</span>
                      <p className="text-slate-200 mt-0.5">{activeDiscrepancy.resolutionReason}</p>
                    </div>
                  )}
                </div>

                <div className="flex justify-end pt-2">
                  <Button variant="secondary" onClick={() => setIsTriageOpen(false)}>
                    Close
                  </Button>
                </div>
              </div>
            )}
          </form>
        )}
      </Modal>
    </div>
  );
};
