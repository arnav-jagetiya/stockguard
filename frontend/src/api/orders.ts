import { apiClient } from './client';
import type { CreateOrderRequest, Order, OrderDetail, OrderStatus } from '../types/order';
import type { Reservation } from '../types/reservation';

export async function getOrdersApi(
  status?: OrderStatus,
  limit: number = 50,
  signal?: AbortSignal
): Promise<Order[]> {
  const q = new URLSearchParams();
  if (status) q.append('status', status);
  q.append('limit', limit.toString());
  const qs = `?${q.toString()}`;
  return apiClient<Order[]>(`/api/orders${qs}`, { signal });
}

export const listOrdersApi = getOrdersApi;

export async function getOrderByIdApi(orderId: string): Promise<OrderDetail> {
  return apiClient<OrderDetail>(`/api/orders/${orderId}`);
}

export const getOrderApi = getOrderByIdApi;

export async function createOrderApi(data: CreateOrderRequest): Promise<Order> {
  return apiClient<Order>('/api/orders', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function reserveOrderApi(orderId: string): Promise<Reservation> {
  return apiClient<Reservation>(`/api/orders/${orderId}/reserve`, {
    method: 'POST',
  });
}

export async function confirmOrderApi(orderId: string): Promise<Order> {
  return apiClient<Order>(`/api/orders/${orderId}/confirm`, {
    method: 'POST',
  });
}

export async function fulfillOrderApi(orderId: string): Promise<Order> {
  return apiClient<Order>(`/api/orders/${orderId}/fulfill`, {
    method: 'POST',
  });
}

export async function cancelOrderApi(orderId: string): Promise<Order> {
  return apiClient<Order>(`/api/orders/${orderId}/cancel`, {
    method: 'POST',
  });
}
