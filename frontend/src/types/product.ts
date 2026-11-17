export interface Product {
  id: string;
  organizationId: string;
  sku: string;
  normalizedSku?: string;
  name: string;
  description?: string | null;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateProductRequest {
  sku: string;
  name: string;
  description?: string;
}

export interface UpdateProductRequest {
  name: string;
  description?: string;
}
