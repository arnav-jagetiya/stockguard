export type ReservationStatus = 'ACTIVE' | 'EXPIRED' | 'CONSUMED' | 'RELEASED';

export interface ReservationAllocation {
  id: string;
  reservationId: string;
  orderItemId: string;
  productId: string;
  warehouseId: string;
  quantity: number;
  createdAt: string;
}

export interface Reservation {
  id: string;
  organizationId: string;
  orderId: string;
  status: ReservationStatus;
  expiresAt: string;
  allocations: ReservationAllocation[];
  createdAt: string;
  updatedAt: string;
}
