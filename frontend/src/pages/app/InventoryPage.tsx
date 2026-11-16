import React, { useEffect, useState } from 'react';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import {
  getBalancesApi,
  listTransactionsApi,
  receiveStockApi,
  adjustStockApi,
  recordOpeningBalanceApi,
} from '../../api/inventory';
import { listProductsApi } from '../../api/products';
import { listWarehousesApi } from '../../api/warehouses';
import type { InventoryBalance, InventoryTransaction } from '../../types/inventory';
import type { Product } from '../../types/product';
import type { Warehouse as WarehouseType } from '../../types/warehouse';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { Select } from '../../components/common/Select';
import { Modal } from '../../components/common/Modal';
import { Badge } from '../../components/common/Badge';
import { Table, TableHead, TableHeader, TableBody, TableRow, TableCell } from '../../components/common/Table';
import { TableSkeleton } from '../../components/common/Skeleton';
import { EmptyState } from '../../components/common/EmptyState';
import {
  Boxes,
  ArrowDownLeft,
  SlidersHorizontal,
  BookmarkPlus,
  History,
} from 'lucide-react';

export const InventoryPage: React.FC = () => {
  const { hasPermission } = useAuth();
  const toast = useToast();
  const canReceive = hasPermission('inventory:receive');
  const canAdjust = hasPermission('inventory:adjust');

  const [activeTab, setActiveTab] = useState<'balances' | 'transactions'>('balances');
  const [balances, setBalances] = useState<InventoryBalance[]>([]);
  const [transactions, setTransactions] = useState<InventoryTransaction[]>([]);
  const [products, setProducts] = useState<Product[]>([]);
  const [warehouses, setWarehouses] = useState<WarehouseType[]>([]);

  // Filter states
  const [selectedProduct, setSelectedProduct] = useState('');
  const [selectedWarehouse, setSelectedWarehouse] = useState('');
  const [isLoading, setIsLoading] = useState(true);

  // Modals
  const [isReceiveOpen, setIsReceiveOpen] = useState(false);
  const [isAdjustOpen, setIsAdjustOpen] = useState(false);
  const [isOpeningOpen, setIsOpeningOpen] = useState(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  // Modal form states
  const [modalProduct, setModalProduct] = useState('');
  const [modalWarehouse, setModalWarehouse] = useState('');
  const [modalQuantity, setModalQuantity] = useState('1');
  const [modalDelta, setModalDelta] = useState('1');
  const [modalReason, setModalReason] = useState('');
  const [modalNotes, setModalNotes] = useState('');

  const loadData = async () => {
    setIsLoading(true);
    try {
      const [prods, whs] = await Promise.all([
        listProductsApi(undefined, true),
        listWarehousesApi(undefined, true),
      ]);
      setProducts(prods);
      setWarehouses(whs);

      if (activeTab === 'balances') {
        const balData = await getBalancesApi(selectedProduct || undefined, selectedWarehouse || undefined);
        setBalances(balData);
      } else {
        const txData = await listTransactionsApi(50);
        setTransactions(txData);
      }
    } catch (err: unknown) {
      if (err instanceof Error) toast.error(err.message);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    loadData();
  }, [activeTab, selectedProduct, selectedWarehouse]);

  const handleReceiveStock = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);
    setIsSubmitting(true);
    try {
      await receiveStockApi({
        productId: modalProduct,
        warehouseId: modalWarehouse,
        quantity: parseInt(modalQuantity, 10),
        notes: modalNotes.trim() || undefined,
      });
      setIsReceiveOpen(false);
      toast.success('Stock received and ledger entry recorded successfully.');
      await loadData();
    } catch (err: unknown) {
      if (err instanceof Error) setFormError(err.message);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleAdjustStock = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);
    setIsSubmitting(true);
    try {
      await adjustStockApi({
        productId: modalProduct,
        warehouseId: modalWarehouse,
        delta: parseInt(modalDelta, 10),
        reason: modalReason.trim(),
      });
      setIsAdjustOpen(false);
      toast.success('Stock adjusted with mandatory audit reason recorded.');
      await loadData();
    } catch (err: unknown) {
      if (err instanceof Error) setFormError(err.message);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleOpeningBalance = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);
    setIsSubmitting(true);
    try {
      await recordOpeningBalanceApi({
        productId: modalProduct,
        warehouseId: modalWarehouse,
        quantity: parseInt(modalQuantity, 10),
        notes: modalNotes.trim() || undefined,
      });
      setIsOpeningOpen(false);
      toast.success('Opening balance established successfully.');
      await loadData();
    } catch (err: unknown) {
      if (err instanceof Error) setFormError(err.message);
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
    return w ? `${w.code} (${w.name})` : id;
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-slate-900 dark:text-white tracking-tight">Inventory Balances & Ledger</h1>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
            Real-time physical on-hand, locked reservations, and double-entry transaction ledgers.
          </p>
        </div>

        <div className="flex items-center gap-2">
          {canReceive && (
            <>
              <Button
                variant="outline"
                size="sm"
                onClick={() => {
                  setFormError(null);
                  if (products[0]) setModalProduct(products[0].id);
                  if (warehouses[0]) setModalWarehouse(warehouses[0].id);
                  setIsOpeningOpen(true);
                }}
                icon={<BookmarkPlus className="w-3.5 h-3.5" />}
              >
                Opening Balance
              </Button>
              <Button
                size="sm"
                onClick={() => {
                  setFormError(null);
                  if (products[0]) setModalProduct(products[0].id);
                  if (warehouses[0]) setModalWarehouse(warehouses[0].id);
                  setIsReceiveOpen(true);
                }}
                icon={<ArrowDownLeft className="w-3.5 h-3.5" />}
              >
                Receive Stock
              </Button>
            </>
          )}

          {canAdjust && (
            <Button
              variant="secondary"
              size="sm"
              onClick={() => {
                setFormError(null);
                if (products[0]) setModalProduct(products[0].id);
                if (warehouses[0]) setModalWarehouse(warehouses[0].id);
                setIsAdjustOpen(true);
              }}
              icon={<SlidersHorizontal className="w-3.5 h-3.5" />}
            >
              Adjust Stock
            </Button>
          )}
        </div>
      </div>

      {/* Tabs */}
      <div className="flex items-center gap-2 border-b border-slate-200 dark:border-slate-800">
        <button
          onClick={() => setActiveTab('balances')}
          className={`px-4 py-2.5 text-xs font-semibold border-b-2 transition-colors cursor-pointer ${
            activeTab === 'balances'
              ? 'border-cyan-500 dark:border-cyan-400 text-cyan-600 dark:text-cyan-400'
              : 'border-transparent text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200'
          }`}
        >
          Facility Balances
        </button>
        <button
          onClick={() => setActiveTab('transactions')}
          className={`px-4 py-2.5 text-xs font-semibold border-b-2 transition-colors cursor-pointer ${
            activeTab === 'transactions'
              ? 'border-cyan-500 dark:border-cyan-400 text-cyan-600 dark:text-cyan-400'
              : 'border-transparent text-slate-500 dark:text-slate-400 hover:text-slate-800 dark:hover:text-slate-200'
          }`}
        >
          Transaction Ledger History
        </button>
      </div>

      {/* Filters (for balances) */}
      {activeTab === 'balances' && (
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 p-3.5 rounded-xl bg-white dark:bg-[#0f172a] border border-slate-200 dark:border-slate-800 shadow-sm">
          <Select
            label="Filter by Product"
            value={selectedProduct}
            onChange={(e) => setSelectedProduct(e.target.value)}
            options={[
              { value: '', label: 'All Products' },
              ...products.map((p) => ({ value: p.id, label: `${p.sku} — ${p.name}` })),
            ]}
          />
          <Select
            label="Filter by Warehouse"
            value={selectedWarehouse}
            onChange={(e) => setSelectedWarehouse(e.target.value)}
            options={[
              { value: '', label: 'All Facilities' },
              ...warehouses.map((w) => ({ value: w.id, label: `${w.code} — ${w.name}` })),
            ]}
          />
        </div>
      )}

      {/* Main Content */}
      {isLoading ? (
        <TableSkeleton rows={5} cols={6} />
      ) : activeTab === 'balances' ? (
        balances.length === 0 ? (
          <EmptyState
            icon={<Boxes className="w-8 h-8" />}
            title="No Balances Found"
            description="No inventory balances have been established yet for these filters."
            actionLabel={canReceive ? 'Record Opening Stock' : undefined}
            onAction={() => {
              if (products[0]) setModalProduct(products[0].id);
              if (warehouses[0]) setModalWarehouse(warehouses[0].id);
              setIsOpeningOpen(true);
            }}
          />
        ) : (
          <Table>
            <TableHead>
              <tr>
                <TableHeader>Product (SKU)</TableHeader>
                <TableHeader>Warehouse</TableHeader>
                <TableHeader>On Hand</TableHeader>
                <TableHeader>Reserved</TableHeader>
                <TableHeader>Available</TableHeader>
                <TableHeader>Last Updated</TableHeader>
              </tr>
            </TableHead>
            <TableBody>
              {balances.map((b) => (
                <TableRow key={b.id}>
                  <TableCell className="font-semibold text-slate-900 dark:text-white">
                    {getProductName(b.productId)}
                  </TableCell>
                  <TableCell className="font-mono text-cyan-600 dark:text-cyan-400">
                    {getWarehouseName(b.warehouseId)}
                  </TableCell>
                  <TableCell className="font-mono font-bold text-slate-800 dark:text-slate-100">{b.onHand}</TableCell>
                  <TableCell className="font-mono font-bold text-indigo-600 dark:text-indigo-400">{b.reserved}</TableCell>
                  <TableCell className="font-mono font-bold text-emerald-600 dark:text-emerald-400">{b.available}</TableCell>
                  <TableCell className="font-mono text-slate-400 dark:text-slate-500 text-[11px]">
                    {new Date(b.updatedAt).toLocaleString()}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )
      ) : (
        /* Ledger Transactions Table */
        transactions.length === 0 ? (
          <EmptyState
            icon={<History className="w-8 h-8" />}
            title="No Ledger Transactions"
            description="The immutable inventory transaction ledger is currently empty."
          />
        ) : (
          <Table>
            <TableHead>
              <tr>
                <TableHeader>Transaction ID</TableHeader>
                <TableHeader>Type</TableHeader>
                <TableHeader>Reference</TableHeader>
                <TableHeader>Notes</TableHeader>
                <TableHeader>Recorded At</TableHeader>
                <TableHeader>Ledger Entries</TableHeader>
              </tr>
            </TableHead>
            <TableBody>
              {transactions.map((tx) => (
                <TableRow key={tx.id}>
                  <TableCell className="font-mono text-xs text-slate-500 dark:text-slate-400 truncate max-w-[120px]">
                    {tx.id}
                  </TableCell>
                  <TableCell>
                    <Badge variant="info">{tx.transactionType}</Badge>
                  </TableCell>
                  <TableCell className="font-mono text-xs text-slate-700 dark:text-slate-300">
                    {tx.referenceType ? `${tx.referenceType}: ${tx.referenceId}` : '—'}
                  </TableCell>
                  <TableCell className="text-xs text-slate-500 dark:text-slate-400 max-w-xs truncate">
                    {tx.notes || '—'}
                  </TableCell>
                  <TableCell className="font-mono text-xs text-slate-400 dark:text-slate-500">
                    {new Date(tx.createdAt).toLocaleString()}
                  </TableCell>
                  <TableCell className="font-mono text-xs text-slate-700 dark:text-slate-300">
                    {tx.entries ? `${tx.entries.length} deltas` : '0'}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )
      )}

      {/* Receive Modal */}
      <Modal
        isOpen={isReceiveOpen}
        onClose={() => setIsReceiveOpen(false)}
        title="Receive Stock"
        description="Record an incoming purchase or replenishment delivery into a facility."
      >
        <form onSubmit={handleReceiveStock} className="space-y-4">
          {formError && (
            <div className="p-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-rose-600 dark:text-rose-400 text-xs">
              {formError}
            </div>
          )}
          <Select
            label="Product"
            value={modalProduct}
            onChange={(e) => setModalProduct(e.target.value)}
            options={products.map((p) => ({ value: p.id, label: `${p.sku} — ${p.name}` }))}
          />
          <Select
            label="Destination Facility"
            value={modalWarehouse}
            onChange={(e) => setModalWarehouse(e.target.value)}
            options={warehouses.map((w) => ({ value: w.id, label: `${w.code} — ${w.name}` }))}
          />
          <Input
            label="Quantity to Receive"
            type="number"
            min="1"
            value={modalQuantity}
            onChange={(e) => setModalQuantity(e.target.value)}
            required
          />
          <Input
            label="Notes / PO Number"
            placeholder="e.g. PO-2026-904"
            value={modalNotes}
            onChange={(e) => setModalNotes(e.target.value)}
          />
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="ghost" onClick={() => setIsReceiveOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={isSubmitting}>
              Confirm Receipt
            </Button>
          </div>
        </form>
      </Modal>

      {/* Adjust Modal */}
      <Modal
        isOpen={isAdjustOpen}
        onClose={() => setIsAdjustOpen(false)}
        title="Adjust Inventory Stock"
        description="Record manual cycle count correction or damage delta. Mandatory audit reason required."
      >
        <form onSubmit={handleAdjustStock} className="space-y-4">
          {formError && (
            <div className="p-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-rose-600 dark:text-rose-400 text-xs">
              {formError}
            </div>
          )}
          <Select
            label="Product"
            value={modalProduct}
            onChange={(e) => setModalProduct(e.target.value)}
            options={products.map((p) => ({ value: p.id, label: `${p.sku} — ${p.name}` }))}
          />
          <Select
            label="Facility"
            value={modalWarehouse}
            onChange={(e) => setModalWarehouse(e.target.value)}
            options={warehouses.map((w) => ({ value: w.id, label: `${w.code} — ${w.name}` }))}
          />
          <Input
            label="Delta (Positive to add, Negative to subtract)"
            type="number"
            value={modalDelta}
            onChange={(e) => setModalDelta(e.target.value)}
            required
            helperText="e.g. +10 or -5 units"
          />
          <Input
            label="Mandatory Adjustment Reason"
            placeholder="e.g. Physical cycle count discrepancy verified"
            value={modalReason}
            onChange={(e) => setModalReason(e.target.value)}
            required
          />
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="ghost" onClick={() => setIsAdjustOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={isSubmitting}>
              Apply Adjustment
            </Button>
          </div>
        </form>
      </Modal>

      {/* Opening Balance Modal */}
      <Modal
        isOpen={isOpeningOpen}
        onClose={() => setIsOpeningOpen(false)}
        title="Record Opening Balance"
        description="Initialize baseline inventory balance for an onboarding SKU at a warehouse."
      >
        <form onSubmit={handleOpeningBalance} className="space-y-4">
          {formError && (
            <div className="p-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-rose-600 dark:text-rose-400 text-xs">
              {formError}
            </div>
          )}
          <Select
            label="Product"
            value={modalProduct}
            onChange={(e) => setModalProduct(e.target.value)}
            options={products.map((p) => ({ value: p.id, label: `${p.sku} — ${p.name}` }))}
          />
          <Select
            label="Facility"
            value={modalWarehouse}
            onChange={(e) => setModalWarehouse(e.target.value)}
            options={warehouses.map((w) => ({ value: w.id, label: `${w.code} — ${w.name}` }))}
          />
          <Input
            label="Initial Quantity"
            type="number"
            min="1"
            value={modalQuantity}
            onChange={(e) => setModalQuantity(e.target.value)}
            required
          />
          <Input
            label="Notes"
            placeholder="e.g. Initial migration baseline"
            value={modalNotes}
            onChange={(e) => setModalNotes(e.target.value)}
          />
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="ghost" onClick={() => setIsOpeningOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={isSubmitting}>
              Record Opening Balance
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
};
