export type OrderStatus = 'PENDING' | 'CONFIRMED' | 'FULFILLING' | 'FULFILLED' | 'CANCELLED' | 'EXPIRED';

export interface OrderItem {
  id: string;
  orderId: string;
  productId: string;
  skuSnapshot: string;
  nameSnapshot: string;
  quantity: number;
  unitPrice: number;
}

export interface Order {
  id: string;
  organizationId: string;
  orderNumber: string;
  customerReference?: string | null;
  externalOrderReference?: string | null;
  status: OrderStatus;
  currency: string;
  totalAmount: number;
  createdAt: string;
  updatedAt: string;
}

export interface OrderDetail extends Order {
  items: OrderItem[];
  actorUserId?: string | null;
}

export interface OrderItemRequest {
  productId: string;
  quantity: number;
  unitPrice: number;
}

export interface CreateOrderRequest {
  customerReference?: string;
  externalOrderReference?: string;
  currency: string;
  items: OrderItemRequest[];
}
