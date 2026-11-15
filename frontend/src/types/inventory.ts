export interface InventoryBalance {
  id: string;
  organizationId: string;
  productId: string;
  warehouseId: string;
  onHand: number;
  reserved: number;
  available: number;
  updatedAt: string;
}

export interface LedgerEntryDto {
  id: string;
  transactionId: string;
  entryNumber: number;
  productId: string;
  warehouseId: string;
  onHandDelta: number;
  reservedDelta: number;
  transferItemId?: string | null;
  transferDirection?: string | null;
  createdAt: string;
}

export interface InventoryTransaction {
  id: string;
  organizationId: string;
  transactionType: string;
  referenceType?: string | null;
  referenceId?: string | null;
  notes?: string | null;
  actorUserId?: string | null;
  createdAt: string;
  entries: LedgerEntryDto[];
}

export interface ReceiveStockRequest {
  productId: string;
  warehouseId: string;
  quantity: number;
  referenceId?: string;
  notes?: string;
}

export interface AdjustStockRequest {
  productId: string;
  warehouseId: string;
  delta: number;
  reason: string;
  referenceId?: string;
}

export interface OpeningBalanceRequest {
  productId: string;
  warehouseId: string;
  quantity: number;
  notes?: string;
}
