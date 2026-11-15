import React, { useState } from 'react';
import { getReservationApi, getReservationByOrderIdApi } from '../../api/reservations';
import type { Reservation } from '../../types/reservation';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { Card } from '../../components/common/Card';
import { Badge } from '../../components/common/Badge';
import { Table, TableHead, TableHeader, TableBody, TableRow, TableCell } from '../../components/common/Table';
import { LoadingSpinner } from '../../components/common/LoadingSpinner';
import { BookmarkCheck, Search } from 'lucide-react';

export const ReservationsPage: React.FC = () => {
  const [searchId, setSearchId] = useState('');
  const [searchType, setSearchType] = useState<'order' | 'reservation'>('order');
  const [reservation, setReservation] = useState<Reservation | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const handleSearch = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!searchId.trim()) return;
    setIsLoading(true);
    setError(null);
    setReservation(null);
    try {
      let res: Reservation;
      if (searchType === 'order') {
        res = await getReservationByOrderIdApi(searchId.trim());
      } else {
        res = await getReservationApi(searchId.trim());
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

  const getStatusBadge = (st: string) => {
    switch (st) {
      case 'ACTIVE':
        return <Badge variant="success" dot>ACTIVE</Badge>;
      case 'EXPIRED':
        return <Badge variant="warning">EXPIRED</Badge>;
      case 'FULFILLED':
        return <Badge variant="info">FULFILLED</Badge>;
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
        <h1 className="text-xl sm:text-2xl font-bold text-white tracking-tight">Active Stock Reservations</h1>
        <p className="text-xs text-slate-400 mt-1">
          Inspect distributed two-phase reservation allocations, expiration deadlines, and multi-warehouse locks.
        </p>
      </div>

      {/* Search Bar */}
      <Card className="space-y-4">
        <form onSubmit={handleSearch} className="flex flex-col sm:flex-row items-end gap-3">
          <div className="w-48">
            <label className="block text-xs font-medium text-slate-400 mb-1.5">Lookup By</label>
            <select
              value={searchType}
              onChange={(e) => setSearchType(e.target.value as 'order' | 'reservation')}
              className="w-full bg-[#0a101d] text-xs text-slate-100 rounded-lg px-3 py-2 border border-slate-800 outline-none"
            >
              <option value="order">Order UUID</option>
              <option value="reservation">Reservation UUID</option>
            </select>
          </div>

          <div className="flex-1">
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
        <LoadingSpinner message="Locating distributed reservation..." />
      ) : error ? (
        <div className="p-4 rounded-xl bg-amber-500/10 border border-amber-500/30 text-amber-300 text-xs">
          {error}
        </div>
      ) : reservation ? (
        <div className="space-y-6 animate-in fade-in duration-150">
          {/* Reservation Summary */}
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            <Card className="space-y-1">
              <span className="text-xs text-slate-400 uppercase font-mono">Status</span>
              <div className="pt-1">{getStatusBadge(reservation.status)}</div>
            </Card>

            <Card className="space-y-1">
              <span className="text-xs text-slate-400 uppercase font-mono">Expires At</span>
              <p className="text-sm font-bold text-white font-mono pt-1">
                {new Date(reservation.expiresAt).toLocaleString()}
              </p>
            </Card>

            <Card className="space-y-1">
              <span className="text-xs text-slate-400 uppercase font-mono">Total Allocation Lines</span>
              <p className="text-sm font-bold text-cyan-400 font-mono pt-1">
                {reservation.allocations.length} line allocations
              </p>
            </Card>
          </div>

          {/* Allocation Table */}
          <div className="space-y-2">
            <h3 className="text-sm font-semibold text-white">Facility Allocation Breakdown</h3>
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
                    <TableCell className="font-mono text-xs text-slate-400">{a.orderItemId}</TableCell>
                    <TableCell className="font-mono text-xs text-cyan-400">{a.productId}</TableCell>
                    <TableCell className="font-mono text-xs text-indigo-400">{a.warehouseId}</TableCell>
                    <TableCell className="font-mono font-bold text-emerald-400">{a.quantity}</TableCell>
                    <TableCell className="font-mono text-xs text-slate-500">
                      {new Date(a.createdAt).toLocaleString()}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        </div>
      ) : (
        <Card className="p-8 text-center text-slate-400 space-y-2">
          <BookmarkCheck className="w-8 h-8 text-slate-500 mx-auto" />
          <h3 className="text-sm font-semibold text-slate-200">No Reservation Selected</h3>
          <p className="text-xs text-slate-500 max-w-sm mx-auto">
            Search by Order UUID to inspect the locked allocations and scheduled expiration deadline.
          </p>
        </Card>
      )}
    </div>
  );
};
