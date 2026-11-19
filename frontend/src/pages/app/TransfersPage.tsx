import React, { useEffect, useState } from 'react';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { listTransfersApi, executeTransferApi } from '../../api/transfers';
import { listWarehousesApi } from '../../api/warehouses';
import { listProductsApi } from '../../api/products';
import type { Transfer, TransferItemRequest } from '../../types/transfer';
import type { Warehouse as WarehouseType } from '../../types/warehouse';
import type { Product } from '../../types/product';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { Select } from '../../components/common/Select';
import { Modal } from '../../components/common/Modal';
import { Badge } from '../../components/common/Badge';
import { Table, TableHead, TableHeader, TableBody, TableRow, TableCell } from '../../components/common/Table';
import { TableSkeleton } from '../../components/common/Skeleton';
import { EmptyState } from '../../components/common/EmptyState';
import { ArrowLeftRight, Plus, Trash2, RotateCcw } from 'lucide-react';

export const TransfersPage: React.FC = () => {
  const { hasPermission } = useAuth();
  const toast = useToast();
  const canExecute = hasPermission('transfer:execute');

  const [transfers, setTransfers] = useState<Transfer[]>([]);
  const [warehouses, setWarehouses] = useState<WarehouseType[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [selectedWarehouse, setSelectedWarehouse] = useState('');
  const [isLoading, setIsLoading] = useState(true);

  // Modal
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  // Form
  const [sourceWh, setSourceWh] = useState('');
  const [destWh, setDestWh] = useState('');
  const [notes, setNotes] = useState('');
  const [items, setItems] = useState<TransferItemRequest[]>([]);

  const loadData = async () => {
    setIsLoading(true);
    try {
      const [txList, whList, prodList] = await Promise.all([
        listTransfersApi(selectedWarehouse || undefined, 50),
        listWarehousesApi(undefined, true),
        listProductsApi(undefined, true),
      ]);
      setTransfers(txList);
      setWarehouses(whList);
      setProducts(prodList);

      if (whList.length >= 2 && !sourceWh) {
        setSourceWh(whList[0].id);
        setDestWh(whList[1].id);
      }
      if (items.length === 0 && prodList.length > 0) {
        setItems([{ productId: prodList[0].id, quantity: 5 }]);
      }
    } catch (err: unknown) {
      if (err instanceof Error) toast.error(err.message);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [selectedWarehouse]);

  const handleExecuteTransfer = async (e: React.FormEvent) => {
    e.preventDefault();
    if (sourceWh === destWh) {
      setFormError('Source and destination warehouse cannot be identical.');
      return;
    }
    if (items.length === 0) {
      setFormError('Transfer must include at least one product line item.');
      return;
    }

    const productIds = items.map((it) => it.productId);
    const hasDuplicates = new Set(productIds).size !== productIds.length;
    if (hasDuplicates) {
      setFormError('Transfer cannot contain duplicate products. Please consolidate quantities into a single line item.');
      return;
    }

    setFormError(null);
    setIsSubmitting(true);
    try {
      await executeTransferApi({
        sourceWarehouseId: sourceWh,
        destinationWarehouseId: destWh,
        notes: notes.trim() || undefined,
        items,
      });
      setIsCreateOpen(false);
      setNotes('');
      toast.success('Inter-facility transfer executed and dual ledger entries posted.');
      await loadData();
    } catch (err: unknown) {
      if (err instanceof Error) setFormError(err.message);
    } finally {
      setIsSubmitting(false);
    }
  };

  const availableProducts = products.filter(
    (p) => !items.some((item) => item.productId === p.id)
  );
  const canAddItem = availableProducts.length > 0;

  const addItem = () => {
    if (!canAddItem) return;
    setItems((prev) => [...prev, { productId: availableProducts[0].id, quantity: 1 }]);
  };

  const removeItem = (idx: number) => {
    setItems((prev) => prev.filter((_, i) => i !== idx));
  };

  const updateItem = (idx: number, field: keyof TransferItemRequest, val: unknown) => {
    setItems((prev) => {
      const copy = [...prev];
      copy[idx] = { ...copy[idx], [field]: val };
      return copy;
    });
  };

  const getWarehouseName = (id: string) => {
    const w = warehouses.find((x) => x.id === id);
    return w ? w.code : id;
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-[#18231D] dark:text-[#EEF3EA] tracking-tight">Facility Transfers</h1>
          <p className="text-xs text-[#657369] dark:text-[#9AA99E] mt-1">
            Execute atomic inter-warehouse stock relocations with dual-ledger verification.
          </p>
        </div>

        {canExecute && (
          <Button
            onClick={() => {
              setFormError(null);
              setIsCreateOpen(true);
            }}
            icon={<Plus className="w-4 h-4" />}
          >
            Execute Transfer
          </Button>
        )}
      </div>

      {/* Filter */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 bg-white dark:bg-[#111A17] p-3.5 rounded-xl border border-[#D9E1D7] dark:border-[#26362E] shadow-sm">
        <div className="w-full sm:w-64">
          <Select
            label="Filter by Facility"
            value={selectedWarehouse}
            onChange={(e) => setSelectedWarehouse(e.target.value)}
            options={[
              { value: '', label: 'All Warehouses' },
              ...warehouses.map((w) => ({ value: w.id, label: `${w.code} (${w.name})` })),
            ]}
          />
        </div>

        <div className="flex items-center gap-3 self-start sm:self-auto pt-1 sm:pt-4 text-xs">
          <span className="text-[#657369] dark:text-[#9AA99E] font-mono">
            Showing {transfers.length} transfer(s)
          </span>
          {selectedWarehouse && (
            <button
              type="button"
              onClick={() => setSelectedWarehouse('')}
              className="inline-flex items-center gap-1 text-[#456B1F] dark:text-[#C6F36B] hover:underline font-medium cursor-pointer"
            >
              <RotateCcw className="w-3 h-3" />
              Reset Facility Filter
            </button>
          )}
        </div>
      </div>

      {/* Table */}
      {isLoading ? (
        <TableSkeleton rows={5} cols={7} />
      ) : transfers.length === 0 ? (
        <EmptyState
          icon={<ArrowLeftRight className="w-8 h-8 text-[#456B1F] dark:text-[#C6F36B]" />}
          title="No Transfers Found"
          description="No stock transfers have been executed between facilities yet."
          actionLabel={canExecute ? 'Execute First Transfer' : undefined}
          onAction={() => setIsCreateOpen(true)}
        />
      ) : (
        <Table>
          <TableHead>
            <tr>
              <TableHeader>Transfer ID</TableHeader>
              <TableHeader>Source Facility</TableHeader>
              <TableHeader>Destination Facility</TableHeader>
              <TableHeader>Line Items</TableHeader>
              <TableHeader>Notes</TableHeader>
              <TableHeader>Executed At</TableHeader>
              <TableHeader>Status</TableHeader>
            </tr>
          </TableHead>
          <TableBody>
            {transfers.map((t) => (
              <TableRow key={t.id}>
                <TableCell className="font-mono text-xs text-[#657369] dark:text-[#9AA99E] truncate max-w-[120px]">
                  {t.id}
                </TableCell>
                <TableCell className="font-mono font-bold text-amber-600 dark:text-amber-400">
                  {getWarehouseName(t.sourceWarehouseId)}
                </TableCell>
                <TableCell className="font-mono font-bold text-[#456B1F] dark:text-[#C6F36B]">
                  {getWarehouseName(t.destinationWarehouseId)}
                </TableCell>
                <TableCell className="font-mono text-xs text-[#18231D] dark:text-[#EEF3EA]">
                  {t.items ? `${t.items.length} product(s)` : '0'}
                </TableCell>
                <TableCell className="text-xs text-[#657369] dark:text-[#9AA99E] truncate max-w-xs">
                  {t.notes || '—'}
                </TableCell>
                <TableCell className="font-mono text-xs text-[#8A988E] dark:text-[#6C7D71]">
                  {new Date(t.createdAt).toLocaleString()}
                </TableCell>
                <TableCell>
                  <Badge variant="success" dot>COMPLETED</Badge>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      {/* Create Modal */}
      <Modal
        isOpen={isCreateOpen}
        onClose={() => setIsCreateOpen(false)}
        title="Execute Inter-Facility Stock Transfer"
        description="Transfer physical on-hand inventory between facilities atomically."
        maxWidth="xl"
      >
        <form onSubmit={handleExecuteTransfer} className="space-y-4">
          {formError && (
            <div className="p-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-rose-600 dark:text-rose-400 text-xs">
              {formError}
            </div>
          )}

          <div className="grid grid-cols-2 gap-3">
            <Select
              label="Source Facility"
              value={sourceWh}
              onChange={(e) => setSourceWh(e.target.value)}
              options={warehouses.map((w) => ({ value: w.id, label: `${w.code} — ${w.name}` }))}
            />
            <Select
              label="Destination Facility"
              value={destWh}
              onChange={(e) => setDestWh(e.target.value)}
              options={warehouses.map((w) => ({ value: w.id, label: `${w.code} — ${w.name}` }))}
            />
          </div>

          <Input
            label="Transfer Notes / Authorization"
            placeholder="e.g. Relocating surplus inventory to North hub"
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
          />

          <div className="space-y-2 pt-2">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-[#18231D] dark:text-[#EEF3EA]">Transfer Items</span>
              <div className="flex items-center gap-2">
                {!canAddItem && products.length > 0 && (
                  <span className="text-[11px] text-[#8A988E] dark:text-[#6C7D71]">All products added</span>
                )}
                <button
                  type="button"
                  onClick={addItem}
                  disabled={!canAddItem}
                  aria-disabled={!canAddItem}
                  className={`text-xs flex items-center gap-1 ${
                    canAddItem
                      ? 'text-[#147C68] dark:text-[#6CD8C0] hover:underline cursor-pointer'
                      : 'text-[#8A988E] dark:text-[#6C7D71] cursor-not-allowed opacity-60'
                  }`}
                  title={canAddItem ? 'Add another product line item' : 'All available products have already been added'}
                >
                  <Plus className="w-3.5 h-3.5" /> Add Product
                </button>
              </div>
            </div>

            <div className="space-y-2 max-h-56 overflow-y-auto pr-1">
              {items.map((it, idx) => {
                const selectableProducts = products.filter(
                  (p) => p.id === it.productId || !items.some((other, oIdx) => oIdx !== idx && other.productId === p.id)
                );
                return (
                  <div key={idx} className="flex items-center gap-2 p-2.5 rounded-xl bg-[#E9EEE6]/50 dark:bg-[#1B2922]/50 border border-[#D9E1D7] dark:border-[#26362E]">
                    <div className="flex-1">
                      <Select
                        aria-label={`Product for line ${idx + 1}`}
                        value={it.productId}
                        onChange={(e) => updateItem(idx, 'productId', e.target.value)}
                        options={selectableProducts.map((p) => ({ value: p.id, label: `${p.sku} — ${p.name}` }))}
                      />
                    </div>
                    <div className="w-28">
                      <Input
                        aria-label={`Quantity for line ${idx + 1}`}
                        type="number"
                        min="1"
                        value={it.quantity}
                        onChange={(e) => updateItem(idx, 'quantity', parseInt(e.target.value, 10) || 1)}
                        required
                      />
                    </div>
                    {items.length > 1 && (
                      <button
                        type="button"
                        onClick={() => removeItem(idx)}
                        aria-label={`Remove product line ${idx + 1}`}
                        className="p-2 text-[#8A988E] hover:text-rose-500 transition-colors cursor-pointer"
                      >
                        <Trash2 className="w-4 h-4" />
                      </button>
                    )}
                  </div>
                );
              })}
            </div>
          </div>

          <div className="flex justify-end gap-2 pt-3 border-t border-[#D9E1D7] dark:border-[#26362E]">
            <Button type="button" variant="ghost" onClick={() => setIsCreateOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={isSubmitting}>
              Execute Relocation
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
};
