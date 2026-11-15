export interface CatalogSummary {
  activeProductsCount: number;
  activeWarehousesCount: number;
}

export interface InventorySummary {
  totalOnHand: number;
  totalReserved: number;
  totalAvailable: number;
  outOfStockActiveProductsCount: number;
}

export interface ReservationsSummary {
  activeReservationsCount: number;
  totalReservedUnits: number;
}

export interface OrderPipelineSummary {
  pendingCount: number;
  confirmedCount: number;
  fulfilledCount: number;
  cancelledCount: number;
}

export interface IntegritySummary {
  openDiscrepanciesCount: number;
}

export interface RecentMovementDto {
  transactionId: string;
  transactionType: string;
  referenceType?: string | null;
  referenceId?: string | null;
  createdAt: string;
  createdByName: string;
}

export interface DashboardSummaryResponse {
  organizationId: string;
  generatedAt: string;
  catalog: CatalogSummary;
  inventory: InventorySummary;
  reservations: ReservationsSummary;
  orders: OrderPipelineSummary;
  integrity: IntegritySummary;
  recentMovements: RecentMovementDto[];
}

export interface WarehouseInventorySummary {
  totalOnHand: number;
  totalReserved: number;
  totalAvailable: number;
  outOfStockProductsCount: number;
}

export interface WarehouseReservationsSummary {
  activeAllocationsCount: number;
  totalAllocatedUnits: number;
}

export interface WarehouseDashboardResponse {
  organizationId: string;
  warehouseId: string;
  warehouseCode: string;
  warehouseName: string;
  active: boolean;
  generatedAt: string;
  inventory: WarehouseInventorySummary;
  reservations: WarehouseReservationsSummary;
  integrity: IntegritySummary;
  recentMovements: RecentMovementDto[];
}
