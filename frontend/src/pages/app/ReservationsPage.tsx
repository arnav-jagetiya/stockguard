import React, { useState, useEffect } from 'react';
import { getReservationApi, getReservationByOrderIdApi } from '../../api/reservations';
import { listOrdersApi } from '../../api/orders';
import type { Reservation } from '../../types/reservation';
import type { Order } from '../../types/order';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { Select } from '../../components/common/Select';
import { Card } from '../../components/common/Card';
import { Badge } from '../../components/common/Badge';
import { Table, TableHead, TableHeader, TableBody, TableRow, TableCell } from '../../components/common/Table';
import { TableSkeleton } from '../../components/common/Skeleton';
import { BookmarkCheck, Search } from 'lucide-react';

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const ReservationsPage: React.FC = () => {
  const [searchId, setSearchId] = useState('');
  const [searchType, setSearchType] = useState<'order' | 'reservation'>('order');
  const [reservation, setReservation] = useState<Reservation | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Recent orders helper to prevent mandatory manual UUID copying
  const [recentOrders, setRecentOrders] = useState<Order[]>([]);
  const [selectedOrderPicker, setSelectedOrderPicker] = useState<string>('');

  useEffect(() => {
    let isMounted = true;
    const fetchRecentOrders = async () => {
      try {
        const orderList = await listOrdersApi(undefined, 30);
        if (isMounted) setRecentOrders(orderList);
      } catch {
        // Non-blocking helper fetch
      }
    };
    fetchRecentOrders();
    return () => { isMounted = false; };
  }, []);

  const executeLookup = async (id: string, type: 'order' | 'reservation') => {
    const trimmed = id.trim();
    if (!trimmed) return;

    if (!UUID_REGEX.test(trimmed)) {
      setError(`Invalid UUID format. Expected a 36-character hexadecimal UUID, but received: "${trimmed}". Select an order from the list or verify the identifier.`);
      setReservation(null);
      return;
    }

    setIsLoading(true);
    setError(null);
    setReservation(null);
    try {
      let res: Reservation;
      if (type === 'order') {
        res = await getReservationByOrderIdApi(trimmed);
      } else {
        res = await getReservationApi(trimmed);
      }
      setReservation(res);
    } catch (err: unknown) {
      if (err instanceof Error) {
        setError(err.message);
      } else {
        setError('No active reservation record found for this identifier.');
      }
    } finally {
      setIsLoading(false);
    }
  };

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    executeLookup(searchId, searchType);
  };

  const handleOrderPickerChange = (orderId: string) => {
    setSelectedOrderPicker(orderId);
    if (orderId) {
      setSearchType('order');
      setSearchId(orderId);
      executeLookup(orderId, 'order');
    }
  };

  const getStatusBadge = (st: string) => {
    switch (st) {
      case 'ACTIVE':
        return <Badge variant="success" dot>ACTIVE</Badge>;
      case 'EXPIRED':
        return <Badge variant="warning">EXPIRED</Badge>;
      case 'CONSUMED':
      case 'FULFILLED':
        return <Badge variant="info">CONSUMED</Badge>;
      case 'RELEASED':
        return <Badge variant="neutral">RELEASED</Badge>;
      default:
        return <Badge>{st}</Badge>;
    }
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <h1 className="text-xl sm:text-2xl font-bold text-[#18231D] dark:text-[#EEF3EA] tracking-tight">Active Stock Reservations</h1>
        <p className="text-xs text-[#657369] dark:text-[#9AA99E] mt-1">
          Inspect distributed two-phase reservation allocations, expiration deadlines, and multi-warehouse locks.
        </p>
      </div>

      {/* Lookup Controls */}
      <Card className="space-y-4">
        {recentOrders.length > 0 && (
          <div className="pb-3 border-b border-[#D9E1D7] dark:border-[#26362E]">
            <Select
              label="Quick Picker — Select From Active / Recent Orders"
              value={selectedOrderPicker}
              onChange={(e) => handleOrderPickerChange(e.target.value)}
              options={[
                { value: '', label: 'Select an order to inspect its stock allocations...' },
                ...recentOrders.map((o) => ({
                  value: o.id,
                  label: `${o.orderNumber} (${o.status}) — ${o.customerReference || 'No customer ref'}`,
                })),
              ]}
            />
          </div>
        )}

        <form onSubmit={handleSearch} className="flex flex-col sm:flex-row items-end gap-3">
          <div className="w-full sm:w-48">
            <Select
              label="Lookup By"
              value={searchType}
              onChange={(e) => setSearchType(e.target.value as 'order' | 'reservation')}
              options={[
                { value: 'order', label: 'Order UUID' },
                { value: 'reservation', label: 'Reservation UUID' },
              ]}
            />
          </div>

          <div className="flex-1 w-full">
            <Input
              label={searchType === 'order' ? 'Enter Order ID (UUID)' : 'Enter Reservation ID (UUID)'}
              placeholder="e.g. 550e8400-e29b-41d4-a716-446655440000"
              value={searchId}
              onChange={(e) => setSearchId(e.target.value)}
              required
            />
          </div>

          <Button type="submit" isLoading={isLoading} icon={<Search className="w-4 h-4" />}>
            Lookup Allocation
          </Button>
        </form>
      </Card>

      {/* Results */}
      {isLoading ? (
        <TableSkeleton rows={4} cols={5} />
      ) : error ? (
        <div className="p-4 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-700 dark:text-amber-300 text-xs">
          {error}
        </div>
      ) : reservation ? (
        <div className="space-y-6 animate-in fade-in duration-150">
          {/* Reservation Summary */}
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            <Card className="space-y-1">
              <span className="text-xs text-[#657369] dark:text-[#9AA99E] uppercase font-mono">Status</span>
              <div className="pt-1">{getStatusBadge(reservation.status)}</div>
            </Card>

            <Card className="space-y-1">
              <span className="text-xs text-[#657369] dark:text-[#9AA99E] uppercase font-mono">Expires At</span>
              <p className="text-sm font-bold text-[#18231D] dark:text-[#EEF3EA] font-mono pt-1">
                {new Date(reservation.expiresAt).toLocaleString()}
              </p>
            </Card>

            <Card className="space-y-1">
              <span className="text-xs text-[#657369] dark:text-[#9AA99E] uppercase font-mono">Total Allocation Lines</span>
              <p className="text-sm font-bold text-[#147C68] dark:text-[#6CD8C0] font-mono pt-1">
                {reservation.allocations.length} line allocations
              </p>
            </Card>
          </div>

          {/* Allocation Table */}
          <div className="space-y-2">
            <h3 className="text-sm font-semibold text-[#18231D] dark:text-[#EEF3EA]">Facility Allocation Breakdown</h3>
            <Table>
              <TableHead>
                <tr>
                  <TableHeader>Order Item ID</TableHeader>
                  <TableHeader>Product ID</TableHeader>
                  <TableHeader>Warehouse ID</TableHeader>
                  <TableHeader>Allocated Quantity</TableHeader>
                  <TableHeader>Allocated At</TableHeader>
                </tr>
              </TableHead>
              <TableBody>
                {reservation.allocations.map((a) => (
                  <TableRow key={a.id}>
                    <TableCell className="font-mono text-xs text-[#657369] dark:text-[#9AA99E]">{a.orderItemId}</TableCell>
                    <TableCell className="font-mono text-xs text-[#147C68] dark:text-[#6CD8C0] font-semibold">{a.productId}</TableCell>
                    <TableCell className="font-mono text-xs text-[#8A988E] dark:text-[#6C7D71]">{a.warehouseId}</TableCell>
                    <TableCell className="font-mono font-bold text-[#456B1F] dark:text-[#C6F36B]">{a.quantity}</TableCell>
                    <TableCell className="font-mono text-xs text-[#8A988E] dark:text-[#6C7D71]">
                      {new Date(a.createdAt).toLocaleString()}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </div>
      ) : (
        <Card className="p-8 text-center text-[#657369] dark:text-[#9AA99E] space-y-2">
          <BookmarkCheck className="w-8 h-8 text-[#456B1F] dark:text-[#C6F36B] mx-auto" />
          <h3 className="text-sm font-semibold text-[#18231D] dark:text-[#EEF3EA]">No Reservation Selected</h3>
          <p className="text-xs text-[#657369] dark:text-[#9AA99E] max-w-sm mx-auto">
            Search by Order UUID to inspect the locked allocations and scheduled expiration deadline.
          </p>
        </Card>
      )}
    </div>
  );
};
