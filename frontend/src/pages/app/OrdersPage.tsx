import React, { useEffect, useState } from 'react';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import {
  listOrdersApi,
  getOrderApi,
  createOrderApi,
  confirmOrderApi,
  fulfillOrderApi,
  cancelOrderApi,
} from '../../api/orders';
import { reserveOrderApi } from '../../api/reservations';
import { listProductsApi } from '../../api/products';
import type { Order, OrderDetail, OrderItemRequest, OrderStatus } from '../../types/order';
import type { Product } from '../../types/product';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { Select } from '../../components/common/Select';
import { Modal } from '../../components/common/Modal';
import { Badge } from '../../components/common/Badge';
import { Table, TableHead, TableHeader, TableBody, TableRow, TableCell } from '../../components/common/Table';
import { TableSkeleton } from '../../components/common/Skeleton';
import { ConfirmDialog } from '../../components/common/ConfirmDialog';
import { EmptyState } from '../../components/common/EmptyState';
import {
  ShoppingCart,
  Plus,
  Trash2,
  XCircle,
  Eye,
} from 'lucide-react';

export const OrdersPage: React.FC = () => {
  const { hasPermission } = useAuth();
  const toast = useToast();
  const canCreate = hasPermission('order:create');
  const canConfirm = hasPermission('order:confirm');
  const canFulfill = hasPermission('order:fulfill');
  const canCancel = hasPermission('order:cancel');

  const [orders, setOrders] = useState<Order[]>([]);
  const [selectedStatus, setSelectedStatus] = useState<string>('');
  const [products, setProducts] = useState<Product[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  // Modals
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [inspectOrder, setInspectOrder] = useState<OrderDetail | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  // Cancel Confirmation
  const [orderToCancel, setOrderToCancel] = useState<string | null>(null);
  const [isCancelling, setIsCancelling] = useState(false);

  // Create form state
  const [customerRef, setCustomerRef] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [items, setItems] = useState<OrderItemRequest[]>([]);

  const fetchOrders = async () => {
    setIsLoading(true);
    try {
      const [orderList, prodList] = await Promise.all([
        listOrdersApi((selectedStatus as OrderStatus) || undefined, 50),
        listProductsApi(undefined, true),
      ]);
      setOrders(orderList);
      setProducts(prodList);
      if (items.length === 0 && prodList.length > 0) {
        setItems([{ productId: prodList[0].id, quantity: 1, unitPrice: prodList[0].price || 10 }]);
      }
    } catch (err: unknown) {
      if (err instanceof Error) toast.error(err.message);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchOrders();
  }, [selectedStatus]);

  const handleCreateOrder = async (e: React.FormEvent) => {
    e.preventDefault();
    if (items.length === 0) {
      setActionError('At least one line item is required.');
      return;
    }
    setActionError(null);
    setIsSubmitting(true);
    try {
      await createOrderApi({
        customerReference: customerRef.trim() || undefined,
        currency: currency.toUpperCase(),
        items,
      });
      setIsCreateOpen(false);
      setCustomerRef('');
      toast.success('Order created successfully.');
      await fetchOrders();
    } catch (err: unknown) {
      if (err instanceof Error) setActionError(err.message);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleInspect = async (id: string) => {
    try {
      const detail = await getOrderApi(id);
      setInspectOrder(detail);
    } catch (err: unknown) {
      if (err instanceof Error) toast.error(err.message);
    }
  };

  const handleReserve = async (orderId: string) => {
    setActionError(null);
    try {
      await reserveOrderApi(orderId);
      toast.success('Stock reserved for order.');
      await fetchOrders();
      if (inspectOrder?.id === orderId) handleInspect(orderId);
    } catch (err: unknown) {
      if (err instanceof Error) toast.error(`Reservation failed: ${err.message}`);
    }
  };

  const handleConfirm = async (orderId: string) => {
    try {
      await confirmOrderApi(orderId);
      toast.success('Order confirmed.');
      await fetchOrders();
      if (inspectOrder?.id === orderId) handleInspect(orderId);
    } catch (err: unknown) {
      if (err instanceof Error) toast.error(`Confirmation failed: ${err.message}`);
    }
  };

  const handleFulfill = async (orderId: string) => {
    try {
      await fulfillOrderApi(orderId);
      toast.success('Order fulfilled and inventory ledger deducted.');
      await fetchOrders();
      if (inspectOrder?.id === orderId) handleInspect(orderId);
    } catch (err: unknown) {
      if (err instanceof Error) toast.error(`Fulfillment failed: ${err.message}`);
    }
  };

  const confirmCancelOrder = async () => {
    if (!orderToCancel) return;
    setIsCancelling(true);
    try {
      await cancelOrderApi(orderToCancel);
      toast.success('Order cancelled and reserved stock released.');
      await fetchOrders();
      if (inspectOrder?.id === orderToCancel) handleInspect(orderToCancel);
      setOrderToCancel(null);
    } catch (err: unknown) {
      if (err instanceof Error) toast.error(`Cancel failed: ${err.message}`);
    } finally {
      setIsCancelling(false);
    }
  };

  const addItemRow = () => {
    if (products.length === 0) return;
    setItems((prev) => [
      ...prev,
      { productId: products[0].id, quantity: 1, unitPrice: products[0].price || 10 },
    ]);
  };

  const removeItemRow = (index: number) => {
    setItems((prev) => prev.filter((_, i) => i !== index));
  };

  const updateItem = (index: number, field: keyof OrderItemRequest, val: unknown) => {
    setItems((prev) => {
      const copy = [...prev];
      copy[index] = { ...copy[index], [field]: val };
      return copy;
    });
  };

  const getStatusBadge = (status: OrderStatus) => {
    switch (status) {
      case 'PENDING':
        return <Badge variant="warning">PENDING</Badge>;
      case 'CONFIRMED':
        return <Badge variant="info">CONFIRMED</Badge>;
      case 'FULFILLED':
        return <Badge variant="success">FULFILLED</Badge>;
      case 'CANCELLED':
        return <Badge variant="neutral">CANCELLED</Badge>;
      default:
        return <Badge>{status}</Badge>;
    }
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-slate-900 dark:text-white tracking-tight">Order Pipeline</h1>
          <p className="text-xs text-slate-500 dark:text-slate-400 mt-1">
            Create customer orders, allocate priority warehouse reservations, and fulfill shipments.
          </p>
        </div>

        {canCreate && (
          <Button
            onClick={() => {
              setActionError(null);
              setIsCreateOpen(true);
            }}
            icon={<Plus className="w-4 h-4" />}
          >
            Create Order
          </Button>
        )}
      </div>

      {/* Filter */}
      <div className="flex items-center gap-4 bg-white dark:bg-[#0f172a] p-3.5 rounded-xl border border-slate-200 dark:border-slate-800 shadow-sm">
        <div className="w-60">
          <Select
            label="Filter by Status"
            value={selectedStatus}
            onChange={(e) => setSelectedStatus(e.target.value)}
            options={[
              { value: '', label: 'All Lifecycle States' },
              { value: 'PENDING', label: 'Pending' },
              { value: 'CONFIRMED', label: 'Confirmed' },
              { value: 'FULFILLED', label: 'Fulfilled' },
              { value: 'CANCELLED', label: 'Cancelled' },
            ]}
          />
        </div>
      </div>

      {/* Table */}
      {isLoading ? (
        <TableSkeleton rows={5} cols={6} />
      ) : orders.length === 0 ? (
        <EmptyState
          icon={<ShoppingCart className="w-8 h-8" />}
          title="No Orders Found"
          description={selectedStatus ? `No orders found in '${selectedStatus}' state.` : 'No customer orders placed yet.'}
          actionLabel={canCreate && !selectedStatus ? 'Create New Order' : undefined}
          onAction={() => setIsCreateOpen(true)}
        />
      ) : (
        <Table>
          <TableHead>
            <tr>
              <TableHeader>Order Number</TableHeader>
              <TableHeader>Customer Ref</TableHeader>
              <TableHeader>Total Amount</TableHeader>
              <TableHeader>Status</TableHeader>
              <TableHeader>Created</TableHeader>
              <TableHeader className="text-right">Actions</TableHeader>
            </tr>
          </TableHead>
          <TableBody>
            {orders.map((o) => (
              <TableRow key={o.id}>
                <TableCell className="font-mono font-semibold text-cyan-600 dark:text-cyan-400">
                  {o.orderNumber}
                </TableCell>
                <TableCell className="text-slate-700 dark:text-slate-300">
                  {o.customerReference || '—'}
                </TableCell>
                <TableCell className="font-mono font-bold text-slate-800 dark:text-slate-100">
                  {o.currency} {Number(o.totalAmount).toFixed(2)}
                </TableCell>
                <TableCell>{getStatusBadge(o.status)}</TableCell>
                <TableCell className="font-mono text-slate-400 dark:text-slate-500 text-[11px]">
                  {new Date(o.createdAt).toLocaleString()}
                </TableCell>
                <TableCell className="text-right">
                  <div className="flex items-center justify-end gap-1.5">
                    <button
                      onClick={() => handleInspect(o.id)}
                      className="p-1.5 text-slate-400 hover:text-slate-900 dark:hover:text-white rounded-lg hover:bg-slate-100 dark:hover:bg-slate-800 transition-colors"
                      title="Inspect Items"
                    >
                      <Eye className="w-4 h-4" />
                    </button>

                    {o.status === 'PENDING' && (
                      <>
                        <button
                          onClick={() => handleReserve(o.id)}
                          className="px-2 py-1 text-xs font-semibold text-cyan-700 dark:text-cyan-400 bg-cyan-50 dark:bg-cyan-500/10 hover:bg-cyan-100 dark:hover:bg-cyan-500/20 rounded-md border border-cyan-200 dark:border-cyan-500/30 transition-colors"
                          title="Reserve inventory"
                        >
                          Reserve
                        </button>

                        {canConfirm && (
                          <button
                            onClick={() => handleConfirm(o.id)}
                            className="px-2 py-1 text-xs font-semibold text-indigo-700 dark:text-indigo-400 bg-indigo-50 dark:bg-indigo-500/10 hover:bg-indigo-100 dark:hover:bg-indigo-500/20 rounded-md border border-indigo-200 dark:border-indigo-500/30 transition-colors"
                            title="Confirm order"
                          >
                            Confirm
                          </button>
                        )}
                      </>
                    )}

                    {o.status === 'CONFIRMED' && canFulfill && (
                      <button
                        onClick={() => handleFulfill(o.id)}
                        className="px-2 py-1 text-xs font-semibold text-emerald-700 dark:text-emerald-400 bg-emerald-50 dark:bg-emerald-500/10 hover:bg-emerald-100 dark:hover:bg-emerald-500/20 rounded-md border border-emerald-200 dark:border-emerald-500/30 transition-colors"
                        title="Fulfill order"
                      >
                        Fulfill
                      </button>
                    )}

                    {o.status !== 'FULFILLED' && o.status !== 'CANCELLED' && canCancel && (
                      <button
                        onClick={() => setOrderToCancel(o.id)}
                        className="p-1.5 text-slate-400 hover:text-rose-600 dark:hover:text-rose-400 rounded-lg hover:bg-rose-50 dark:hover:bg-rose-500/10 transition-colors"
                        title="Cancel Order"
                      >
                        <XCircle className="w-4 h-4" />
                      </button>
                    )}
                  </div>
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
        title="Create Customer Order"
        description="Add a new customer order with line items."
        maxWidth="xl"
      >
        <form onSubmit={handleCreateOrder} className="space-y-4">
          {actionError && (
            <div className="p-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-rose-600 dark:text-rose-400 text-xs">
              {actionError}
            </div>
          )}

          <div className="grid grid-cols-2 gap-3">
            <Input
              label="Customer Reference"
              placeholder="e.g. CUST-PO-7782"
              value={customerRef}
              onChange={(e) => setCustomerRef(e.target.value)}
            />
            <Input
              label="Currency"
              value={currency}
              onChange={(e) => setCurrency(e.target.value.toUpperCase())}
              required
            />
          </div>

          <div className="space-y-2 pt-2">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-slate-700 dark:text-slate-300">Order Items</span>
              <button
                type="button"
                onClick={addItemRow}
                className="text-xs text-cyan-600 dark:text-cyan-400 hover:underline flex items-center gap-1 cursor-pointer"
              >
                <Plus className="w-3.5 h-3.5" /> Add Line Item
              </button>
            </div>

            <div className="space-y-2.5 max-h-56 overflow-y-auto pr-1">
              {items.map((it, idx) => (
                <div key={idx} className="flex items-center gap-2 p-2.5 rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800">
                  <div className="flex-1">
                    <Select
                      value={it.productId}
                      onChange={(e) => updateItem(idx, 'productId', e.target.value)}
                      options={products.map((p) => ({ value: p.id, label: `${p.sku} — ${p.name}` }))}
                    />
                  </div>
                  <div className="w-24">
                    <Input
                      type="number"
                      min="1"
                      value={it.quantity}
                      onChange={(e) => updateItem(idx, 'quantity', parseInt(e.target.value, 10) || 1)}
                      required
                    />
                  </div>
                  <div className="w-28">
                    <Input
                      type="number"
                      step="0.01"
                      value={it.unitPrice}
                      onChange={(e) => updateItem(idx, 'unitPrice', parseFloat(e.target.value) || 0)}
                      required
                    />
                  </div>
                  {items.length > 1 && (
                    <button
                      type="button"
                      onClick={() => removeItemRow(idx)}
                      className="p-2 text-slate-400 hover:text-rose-500 transition-colors"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  )}
                </div>
              ))}
            </div>
          </div>

          <div className="flex justify-end gap-2 pt-3 border-t border-slate-200 dark:border-slate-800">
            <Button type="button" variant="ghost" onClick={() => setIsCreateOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={isSubmitting}>
              Submit Order
            </Button>
          </div>
        </form>
      </Modal>

      {/* Inspect Order Details Modal */}
      <Modal
        isOpen={!!inspectOrder}
        onClose={() => setInspectOrder(null)}
        title={`Order Details · ${inspectOrder?.orderNumber}`}
        description={`Customer Ref: ${inspectOrder?.customerReference || 'N/A'}`}
        maxWidth="lg"
      >
        {inspectOrder && (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-3 p-3 rounded-xl bg-slate-50 dark:bg-slate-900 border border-slate-200 dark:border-slate-800 text-xs font-mono">
              <div>
                <span className="text-slate-500 block">Status:</span>
                <span className="font-bold text-slate-900 dark:text-white">{inspectOrder.status}</span>
              </div>
              <div>
                <span className="text-slate-500 block">Total Amount:</span>
                <span className="font-bold text-emerald-600 dark:text-emerald-400">{inspectOrder.currency} {Number(inspectOrder.totalAmount).toFixed(2)}</span>
              </div>
            </div>

            <div className="space-y-2">
              <span className="text-xs font-semibold text-slate-700 dark:text-slate-300">Order Items</span>
              <div className="rounded-xl border border-slate-200 dark:border-slate-800 overflow-hidden">
                <Table>
                  <TableHead>
                    <tr>
                      <TableHeader>SKU</TableHeader>
                      <TableHeader>Product Name</TableHeader>
                      <TableHeader>Qty</TableHeader>
                      <TableHeader>Price</TableHeader>
                    </tr>
                  </TableHead>
                  <TableBody>
                    {inspectOrder.items.map((it) => (
                      <TableRow key={it.id}>
                        <TableCell className="font-mono text-cyan-600 dark:text-cyan-400">{it.skuSnapshot}</TableCell>
                        <TableCell className="text-slate-700 dark:text-slate-200">{it.nameSnapshot}</TableCell>
                        <TableCell className="font-mono font-bold text-slate-800 dark:text-slate-100">{it.quantity}</TableCell>
                        <TableCell className="font-mono text-slate-700 dark:text-slate-300">{inspectOrder.currency} {Number(it.unitPrice).toFixed(2)}</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </div>
            </div>

            <div className="flex justify-end pt-2">
              <Button variant="secondary" onClick={() => setInspectOrder(null)}>
                Close
              </Button>
            </div>
          </div>
        )}
      </Modal>

      {/* Cancel Order Confirm Dialog */}
      <ConfirmDialog
        isOpen={!!orderToCancel}
        onClose={() => setOrderToCancel(null)}
        onConfirm={confirmCancelOrder}
        title="Cancel Customer Order"
        message="Are you sure you want to cancel this order? Any active warehouse inventory reservations held for this order will be released back into available stock."
        confirmText="Cancel Order"
        variant="danger"
        isLoading={isCancelling}
      />
    </div>
  );
};
