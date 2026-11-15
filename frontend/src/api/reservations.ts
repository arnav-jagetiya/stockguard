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

export async function releaseReservationApi(reservationId: string): Promise<{ message: string }> {
  return apiClient<{ message: string }>(`/api/reservations/${reservationId}/release`, {
    method: 'POST',
  });
}
