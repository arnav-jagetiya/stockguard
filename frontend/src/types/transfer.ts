export interface TransferItemResponse {
  id: string;
  productId: string;
  quantity: number;
}

export interface Transfer {
  id: string;
  organizationId: string;
  sourceWarehouseId: string;
  destinationWarehouseId: string;
  notes?: string | null;
  actorUserId?: string | null;
  createdAt: string;
  completedAt?: string | null;
  transactionId?: string | null;
  items: TransferItemResponse[];
}

export interface TransferItemRequest {
  productId: string;
  quantity: number;
}

export interface CreateTransferRequest {
  sourceWarehouseId: string;
  destinationWarehouseId: string;
  notes?: string;
  items: TransferItemRequest[];
}
