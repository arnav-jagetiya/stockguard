export interface Warehouse {
  id: string;
  organizationId: string;
  code: string;
  name: string;
  priority: number;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateWarehouseRequest {
  code: string;
  name: string;
  priority: number;
}

export interface UpdateWarehouseRequest {
  name: string;
  priority: number;
}
