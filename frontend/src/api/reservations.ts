import { apiClient } from './client';
import type { Reservation } from '../types/reservation';
export { reserveOrderApi } from './orders';

export async function getReservationByIdApi(reservationId: string): Promise<Reservation> {
  return apiClient<Reservation>(`/api/reservations/${reservationId}`);
}

export const getReservationApi = getReservationByIdApi;

export async function getReservationByOrderIdApi(orderId: string): Promise<Reservation> {
  return apiClient<Reservation>(`/api/orders/${orderId}/reservation`);
}
