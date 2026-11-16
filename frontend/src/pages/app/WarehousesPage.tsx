import React, { useEffect, useState } from 'react';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import {
  listWarehousesApi,
  createWarehouseApi,
  updateWarehouseApi,
  deactivateWarehouseApi,
} from '../../api/warehouses';
import type { Warehouse as WarehouseType } from '../../types/warehouse';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { Modal } from '../../components/common/Modal';
import { ConfirmDialog } from '../../components/common/ConfirmDialog';
import { Badge } from '../../components/common/Badge';
import { Table, TableHead, TableHeader, TableBody, TableRow, TableCell } from '../../components/common/Table';
import { TableSkeleton } from '../../components/common/Skeleton';
import { EmptyState } from '../../components/common/EmptyState';
import { Warehouse, Plus, Search, Edit2, Ban } from 'lucide-react';

export const WarehousesPage: React.FC = () => {
  const { hasPermission } = useAuth();
  const toast = useToast();
  const canManage = hasPermission('warehouse:manage');

  const [warehouses, setWarehouses] = useState<WarehouseType[]>([]);
  const [search, setSearch] = useState('');
  const [activeOnly, setActiveOnly] = useState(false);
  const [isLoading, setIsLoading] = useState(true);

  // Modal states
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [editingWarehouse, setEditingWarehouse] = useState<WarehouseType | null>(null);
  const [deactivatingWarehouse, setDeactivatingWarehouse] = useState<WarehouseType | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Form states
  const [formCode, setFormCode] = useState('');
  const [formName, setFormName] = useState('');
  const [formPriority, setFormPriority] = useState('1');
  const [formError, setFormError] = useState<string | null>(null);

  const fetchWarehouses = async () => {
    setIsLoading(true);
    try {
      const data = await listWarehousesApi(search || undefined, activeOnly);
      setWarehouses(data);
    } catch (err: unknown) {
      if (err instanceof Error) toast.error('Failed to load warehouses', err.message);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchWarehouses();
  }, [search, activeOnly]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);
    setIsSubmitting(true);
    try {
      await createWarehouseApi({
        code: formCode.trim().toUpperCase(),
        name: formName.trim(),
        priority: parseInt(formPriority, 10) || 1,
      });
      setIsCreateOpen(false);
      resetForm();
      toast.success('Warehouse created', `Facility ${formCode.trim().toUpperCase()} added.`);
      await fetchWarehouses();
    } catch (err: unknown) {
      if (err instanceof Error) setFormError(err.message);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleUpdate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingWarehouse) return;
    setFormError(null);
    setIsSubmitting(true);
    try {
      await updateWarehouseApi(editingWarehouse.id, {
        name: formName.trim(),
        priority: parseInt(formPriority, 10) || 1,
      });
      toast.success('Warehouse updated', `Facility ${editingWarehouse.code} updated.`);
      setEditingWarehouse(null);
      resetForm();
      await fetchWarehouses();
    } catch (err: unknown) {
      if (err instanceof Error) setFormError(err.message);
    } finally {
      setIsSubmitting(false);
    }
  };

  const confirmDeactivate = async () => {
    if (!deactivatingWarehouse) return;
    setIsSubmitting(true);
    try {
      await deactivateWarehouseApi(deactivatingWarehouse.id);
      toast.success('Warehouse deactivated', `Facility ${deactivatingWarehouse.code} is now inactive.`);
      setDeactivatingWarehouse(null);
      await fetchWarehouses();
    } catch (err: unknown) {
      if (err instanceof Error) toast.error('Deactivation failed', err.message);
    } finally {
      setIsSubmitting(false);
    }
  };

  const openEdit = (w: WarehouseType) => {
    setEditingWarehouse(w);
    setFormName(w.name);
    setFormPriority(w.priority.toString());
    setFormError(null);
  };

  const resetForm = () => {
    setFormCode('');
    setFormName('');
    setFormPriority('1');
    setFormError(null);
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-slate-900 dark:text-white tracking-tight">Warehouse Facilities</h1>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
            Manage fulfillment centers, distribution hubs, and allocation priorities.
          </p>
        </div>

        {canManage && (
          <Button
            onClick={() => {
              resetForm();
              setIsCreateOpen(true);
            }}
            icon={<Plus className="w-4 h-4" />}
          >
            Create Facility
          </Button>
        )}
      </div>

      {/* Filter Bar */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-4 bg-white dark:bg-[#0f172a] p-3.5 rounded-xl border border-slate-200 dark:border-slate-800 shadow-xs">
        <div className="relative w-full sm:w-80">
          <Search className="w-4 h-4 text-slate-400 dark:text-slate-500 absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            placeholder="Filter by code or name..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full bg-slate-50 dark:bg-[#0a101d] text-xs text-slate-900 dark:text-slate-100 rounded-lg pl-9 pr-3.5 py-2 border border-slate-300 dark:border-slate-800 outline-none focus:border-cyan-500"
          />
        </div>

        <label className="flex items-center gap-2 text-xs text-slate-600 dark:text-slate-400 cursor-pointer select-none self-start sm:self-auto">
          <input
            type="checkbox"
            checked={activeOnly}
            onChange={(e) => setActiveOnly(e.target.checked)}
            className="rounded border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-900 text-cyan-600 focus:ring-0"
          />
          Show Active Only
        </label>
      </div>

      {/* Table */}
      {isLoading ? (
        <TableSkeleton rows={4} columns={6} />
      ) : warehouses.length === 0 ? (
        <EmptyState
          icon={<Warehouse className="w-8 h-8" />}
          title="No Facilities Found"
          description={search ? 'No warehouses matched your search criteria.' : 'No warehouse facilities configured.'}
          actionLabel={canManage && !search ? 'Add Facility' : undefined}
          onAction={() => {
            resetForm();
            setIsCreateOpen(true);
          }}
        />
      ) : (
        <Table>
          <TableHead>
            <tr>
              <TableHeader>Facility Code</TableHeader>
              <TableHeader>Facility Name</TableHeader>
              <TableHeader>Allocation Priority</TableHeader>
              <TableHeader>Status</TableHeader>
              <TableHeader>Created</TableHeader>
              {canManage && <TableHeader className="text-right">Actions</TableHeader>}
            </tr>
          </TableHead>
          <TableBody>
            {warehouses.map((w) => (
              <TableRow key={w.id}>
                <TableCell className="font-mono font-bold text-cyan-600 dark:text-cyan-400">{w.code}</TableCell>
                <TableCell className="font-semibold text-slate-900 dark:text-white">{w.name}</TableCell>
                <TableCell className="font-mono">
                  <span className="px-2 py-0.5 rounded bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700 text-xs font-semibold text-indigo-700 dark:text-indigo-300">
                    Tier {w.priority}
                  </span>
                </TableCell>
                <TableCell>
                  {w.active ? (
                    <Badge variant="success" dot>ACTIVE</Badge>
                  ) : (
                    <Badge variant="neutral">INACTIVE</Badge>
                  )}
                </TableCell>
                <TableCell className="font-mono text-slate-500">
                  {new Date(w.createdAt).toLocaleDateString()}
                </TableCell>
                {canManage && (
                  <TableCell className="text-right space-x-2">
                    <button
                      onClick={() => openEdit(w)}
                      title="Edit Facility"
                      className="p-1.5 rounded-lg bg-slate-100 hover:bg-slate-200 dark:bg-slate-900 dark:hover:bg-slate-800 text-slate-600 dark:text-slate-400 hover:text-cyan-600 dark:hover:text-cyan-400 border border-slate-200 dark:border-slate-800 transition-colors"
                    >
                      <Edit2 className="w-3.5 h-3.5" />
                    </button>
                    {w.active && (
                      <button
                        onClick={() => setDeactivatingWarehouse(w)}
                        title="Deactivate Facility"
                        className="p-1.5 rounded-lg bg-slate-100 hover:bg-rose-50 dark:bg-slate-900 dark:hover:bg-rose-500/10 text-slate-600 dark:text-slate-400 hover:text-rose-600 dark:hover:text-rose-400 border border-slate-200 dark:border-slate-800 transition-colors"
                      >
                        <Ban className="w-3.5 h-3.5" />
                      </button>
                    )}
                  </TableCell>
                )}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      {/* Create Modal */}
      <Modal
        isOpen={isCreateOpen}
        onClose={() => setIsCreateOpen(false)}
        title="Add Facility"
        description="Configure a new warehouse fulfillment center."
      >
        <form onSubmit={handleCreate} className="space-y-4">
          {formError && (
            <div className="p-2.5 rounded-lg bg-rose-50 dark:bg-rose-500/10 border border-rose-300 dark:border-rose-500/30 text-rose-700 dark:text-rose-400 text-xs">
              {formError}
            </div>
          )}
          <Input
            label="Facility Code"
            placeholder="e.g. WH-EAST-01"
            value={formCode}
            onChange={(e) => setFormCode(e.target.value.toUpperCase())}
            required
          />
          <Input
            label="Facility Name"
            placeholder="e.g. Eastern Regional Distribution Hub"
            value={formName}
            onChange={(e) => setFormName(e.target.value)}
            required
          />
          <Input
            label="Allocation Priority (Lower integer = higher priority)"
            type="number"
            min="1"
            value={formPriority}
            onChange={(e) => setFormPriority(e.target.value)}
            required
          />
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="ghost" onClick={() => setIsCreateOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={isSubmitting}>
              Add Facility
            </Button>
          </div>
        </form>
      </Modal>

      {/* Edit Modal */}
      <Modal
        isOpen={!!editingWarehouse}
        onClose={() => setEditingWarehouse(null)}
        title="Update Facility"
        description={`Modify settings for ${editingWarehouse?.code}`}
      >
        <form onSubmit={handleUpdate} className="space-y-4">
          {formError && (
            <div className="p-2.5 rounded-lg bg-rose-50 dark:bg-rose-500/10 border border-rose-300 dark:border-rose-500/30 text-rose-700 dark:text-rose-400 text-xs">
              {formError}
            </div>
          )}
          <Input
            label="Facility Name"
            value={formName}
            onChange={(e) => setFormName(e.target.value)}
            required
          />
          <Input
            label="Allocation Priority"
            type="number"
            min="1"
            value={formPriority}
            onChange={(e) => setFormPriority(e.target.value)}
            required
          />
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="ghost" onClick={() => setEditingWarehouse(null)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={isSubmitting}>
              Save Changes
            </Button>
          </div>
        </form>
      </Modal>

      {/* Deactivate Confirmation Dialog */}
      <ConfirmDialog
        isOpen={!!deactivatingWarehouse}
        onClose={() => setDeactivatingWarehouse(null)}
        onConfirm={confirmDeactivate}
        title="Deactivate Warehouse Facility"
        message={`Are you sure you want to deactivate facility "${deactivatingWarehouse?.code}"? Inactive warehouses cannot fulfill orders, accept stock receipts, or execute transfers.`}
        confirmLabel="Deactivate"
        variant="danger"
        isLoading={isSubmitting}
      />
    </div>
  );
};
