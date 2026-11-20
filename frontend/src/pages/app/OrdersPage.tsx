import React, { useEffect, useState, useCallback } from 'react';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { isAbortError } from '../../api/client';
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
  Search,
  X,
  RotateCcw,
} from 'lucide-react';

export const OrdersPage: React.FC = () => {
  const { hasPermission } = useAuth();
  const toast = useToast();
  const canCreate = hasPermission('order:create');
  const canReserve = hasPermission('reservation:create');
  const canConfirm = hasPermission('order:confirm');
  const canFulfill = hasPermission('order:fulfill');
  const canCancel = hasPermission('order:cancel');

  const [orders, setOrders] = useState<Order[]>([]);
  const [selectedStatus, setSelectedStatus] = useState<string>('');
  const [searchQuery, setSearchQuery] = useState<string>('');
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

  const fetchOrders = useCallback(async (signal?: AbortSignal) => {
    try {
      const [orderList, prodList] = await Promise.all([
        listOrdersApi((selectedStatus as OrderStatus) || undefined, 50, signal),
        listProductsApi(undefined, true, signal),
      ]);
      if (!signal?.aborted) {
        setOrders(orderList);
        setProducts(prodList);
        setItems((prev) => (prev.length === 0 && prodList.length > 0 ? [{ productId: prodList[0].id, quantity: 1, unitPrice: 10.0 }] : prev));
      }
    } catch (err: unknown) {
      if (isAbortError(err)) return;
      if (!signal?.aborted && err instanceof Error) {
        toast.error(err.message);
      }
    } finally {
      if (!signal?.aborted) {
        setIsLoading(false);
      }
    }
  }, [selectedStatus, toast]);

  useEffect(() => {
    const controller = new AbortController();
    const run = async () => {
      try {
        await fetchOrders(controller.signal);
      } catch (err: unknown) {
        if (isAbortError(err)) return;
      }
    };
    void run();
    return () => {
      controller.abort();
    };
  }, [fetchOrders]);

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
      setIsLoading(true);
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
      setIsLoading(true);
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
      setIsLoading(true);
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
      setIsLoading(true);
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
      setIsLoading(true);
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
      { productId: products[0].id, quantity: 1, unitPrice: 10.0 },
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
      case 'FULFILLING':
        return <Badge variant="accent">FULFILLING</Badge>;
      case 'FULFILLED':
        return <Badge variant="success">FULFILLED</Badge>;
      case 'CANCELLED':
        return <Badge variant="neutral">CANCELLED</Badge>;
      case 'EXPIRED':
        return <Badge variant="error">EXPIRED</Badge>;
      default:
        return <Badge>{status}</Badge>;
    }
  };

  const filteredOrders = orders.filter((o) => {
    if (!searchQuery.trim()) return true;
    const q = searchQuery.toLowerCase().trim();
    const matchesNumber = o.orderNumber.toLowerCase().includes(q);
    const matchesRef = Boolean(o.customerReference && o.customerReference.toLowerCase().includes(q));
    const matchesId = o.id.toLowerCase().includes(q);
    return matchesNumber || matchesRef || matchesId;
  });

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-[#18231D] dark:text-[#EEF3EA] tracking-tight">Order Pipeline</h1>
          <p className="text-xs text-[#657369] dark:text-[#9AA99E] mt-1">
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
      <div className="space-y-3 bg-white dark:bg-[#111A17] p-3.5 rounded-xl border border-[#D9E1D7] dark:border-[#26362E] shadow-sm">
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
          <div>
            <label htmlFor="order-search" className="block text-xs font-semibold text-[#18231D] dark:text-[#EEF3EA] mb-1.5">
              Search Orders
            </label>
            <div className="relative">
              <Search className="w-4 h-4 text-[#8A988E] dark:text-[#6C7D71] absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
              <input
                id="order-search"
                type="text"
                placeholder="Search by order number or customer ref..."
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                className="w-full bg-[#F9FAF7] dark:bg-[#16221E] text-xs text-[#18231D] dark:text-[#EEF3EA] caret-[#18231D] dark:caret-[#EEF3EA] rounded-lg pl-9 pr-8 py-2 border border-[#D9E1D7] dark:border-[#26362E] outline-none focus:border-[#456B1F] dark:focus:border-[#C6F36B] focus:ring-2 focus:ring-[#456B1F]/20 dark:focus:ring-[#C6F36B]/20 transition-colors"
              />
              {searchQuery && (
                <button
                  type="button"
                  onClick={() => setSearchQuery('')}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 text-[#8A988E] hover:text-[#18231D] dark:hover:text-[#EEF3EA] p-0.5 rounded cursor-pointer"
                >
                  <X className="w-3.5 h-3.5" />
                </button>
              )}
            </div>
          </div>

          <Select
            label="Filter by Status"
            value={selectedStatus}
            onChange={(e) => {
              setIsLoading(true);
              setSelectedStatus(e.target.value);
            }}
            options={[
              { value: '', label: 'All Lifecycle States' },
              { value: 'PENDING', label: 'Pending' },
              { value: 'CONFIRMED', label: 'Confirmed' },
              { value: 'FULFILLING', label: 'Fulfilling' },
              { value: 'FULFILLED', label: 'Fulfilled' },
              { value: 'CANCELLED', label: 'Cancelled' },
              { value: 'EXPIRED', label: 'Expired' },
            ]}
          />
        </div>

        <div className="flex items-center justify-between pt-2 border-t border-[#D9E1D7] dark:border-[#26362E] text-xs">
          <span className="text-[#657369] dark:text-[#9AA99E] font-mono">
            Showing {filteredOrders.length} of {orders.length} order(s)
          </span>
          {(selectedStatus || searchQuery) && (
            <button
              type="button"
              onClick={() => {
                setIsLoading(true);
                setSelectedStatus('');
                setSearchQuery('');
              }}
              className="inline-flex items-center gap-1 text-[#456B1F] dark:text-[#C6F36B] hover:underline font-medium cursor-pointer"
            >
              <RotateCcw className="w-3 h-3" />
              Reset Order Filters
            </button>
          )}
        </div>
      </div>

      {/* Table */}
      {isLoading ? (
        <TableSkeleton rows={5} cols={6} />
      ) : filteredOrders.length === 0 ? (
        <EmptyState
          icon={<ShoppingCart className="w-8 h-8 text-[#456B1F] dark:text-[#C6F36B]" />}
          title="No Orders Found"
          description={selectedStatus || searchQuery ? "No orders matched your active filter criteria." : "No customer orders placed yet."}
          actionLabel={canCreate && !selectedStatus && !searchQuery ? 'Create New Order' : undefined}
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
            {filteredOrders.map((o) => (
              <TableRow key={o.id}>
                <TableCell className="font-mono font-semibold text-[#147C68] dark:text-[#6CD8C0]">
                  {o.orderNumber}
                </TableCell>
                <TableCell className="text-[#18231D] dark:text-[#EEF3EA]">
                  {o.customerReference || '—'}
                </TableCell>
                <TableCell className="font-mono font-bold text-[#18231D] dark:text-[#EEF3EA]">
                  {o.currency} {Number(o.totalAmount).toFixed(2)}
                </TableCell>
                <TableCell>{getStatusBadge(o.status)}</TableCell>
                <TableCell className="font-mono text-[#8A988E] dark:text-[#6C7D71] text-[11px]">
                  {new Date(o.createdAt).toLocaleString()}
                </TableCell>
                <TableCell className="text-right">
                  <div className="flex items-center justify-end gap-1.5">
                    <button
                      onClick={() => handleInspect(o.id)}
                      className="p-1.5 text-[#657369] hover:text-[#18231D] dark:hover:text-[#EEF3EA] rounded-lg hover:bg-[#E9EEE6] dark:hover:bg-[#1B2922] transition-colors cursor-pointer"
                      title="Inspect Items"
                    >
                      <Eye className="w-4 h-4" />
                    </button>

                    {o.status === 'PENDING' && (
                      <>
                        {canReserve && (
                          <button
                            onClick={() => handleReserve(o.id)}
                            className="px-2 py-1 text-xs font-semibold text-[#147C68] dark:text-[#6CD8C0] bg-[#147C68]/10 hover:bg-[#147C68]/20 rounded-md border border-[#147C68]/30 transition-colors cursor-pointer"
                            title="Reserve inventory"
                          >
                            Reserve
                          </button>
                        )}

                        {canConfirm && (
                          <button
                            onClick={() => handleConfirm(o.id)}
                            className="px-2 py-1 text-xs font-semibold text-[#456B1F] dark:text-[#C6F36B] bg-[#456B1F]/10 hover:bg-[#456B1F]/20 rounded-md border border-[#456B1F]/30 transition-colors cursor-pointer"
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
                        className="px-2 py-1 text-xs font-semibold text-[#456B1F] dark:text-[#C6F36B] bg-[#456B1F]/10 hover:bg-[#456B1F]/20 rounded-md border border-[#456B1F]/30 transition-colors cursor-pointer"
                        title="Fulfill order"
                      >
                        Fulfill
                      </button>
                    )}

                    {o.status !== 'FULFILLED' && o.status !== 'CANCELLED' && canCancel && (
                      <button
                        onClick={() => setOrderToCancel(o.id)}
                        className="p-1.5 text-[#8A988E] hover:text-rose-600 dark:hover:text-rose-400 rounded-lg hover:bg-rose-50 dark:hover:bg-rose-500/10 transition-colors cursor-pointer"
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
              <span className="text-xs font-semibold text-[#18231D] dark:text-[#EEF3EA]">Order Items</span>
              <button
                type="button"
                onClick={addItemRow}
                className="text-xs text-[#147C68] dark:text-[#6CD8C0] hover:underline flex items-center gap-1 cursor-pointer"
              >
                <Plus className="w-3.5 h-3.5" /> Add Line Item
              </button>
            </div>

            <div className="space-y-2.5 max-h-56 overflow-y-auto pr-1">
              {items.map((it, idx) => (
                <div key={idx} className="flex items-center gap-2 p-2.5 rounded-xl bg-[#E9EEE6]/50 dark:bg-[#1B2922]/50 border border-[#D9E1D7] dark:border-[#26362E]">
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
                      className="p-2 text-[#8A988E] hover:text-rose-500 transition-colors cursor-pointer"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  )}
                </div>
              ))}
            </div>
          </div>

          <div className="flex justify-end gap-2 pt-3 border-t border-[#D9E1D7] dark:border-[#26362E]">
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
            <div className="grid grid-cols-2 gap-3 p-3 rounded-xl bg-[#E9EEE6]/50 dark:bg-[#1B2922]/50 border border-[#D9E1D7] dark:border-[#26362E] text-xs font-mono">
              <div>
                <span className="text-[#657369] dark:text-[#9AA99E] block">Status:</span>
                <span className="font-bold text-[#18231D] dark:text-[#EEF3EA]">{inspectOrder.status}</span>
              </div>
              <div>
                <span className="text-[#657369] dark:text-[#9AA99E] block">Total Amount:</span>
                <span className="font-bold text-[#456B1F] dark:text-[#C6F36B]">{inspectOrder.currency} {Number(inspectOrder.totalAmount).toFixed(2)}</span>
              </div>
            </div>

            <div className="space-y-2">
              <span className="text-xs font-semibold text-[#18231D] dark:text-[#EEF3EA]">Order Items</span>
              <div className="rounded-xl border border-[#D9E1D7] dark:border-[#26362E] overflow-hidden">
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
                        <TableCell className="font-mono text-[#147C68] dark:text-[#6CD8C0] font-semibold">{it.skuSnapshot}</TableCell>
                        <TableCell className="text-[#18231D] dark:text-[#EEF3EA]">{it.nameSnapshot}</TableCell>
                        <TableCell className="font-mono font-bold text-[#18231D] dark:text-[#EEF3EA]">{it.quantity}</TableCell>
                        <TableCell className="font-mono text-[#657369] dark:text-[#9AA99E]">{inspectOrder.currency} {Number(it.unitPrice).toFixed(2)}</TableCell>
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
