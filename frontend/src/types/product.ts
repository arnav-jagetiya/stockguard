export interface Product {
  id: string;
  organizationId: string;
  sku: string;
  name: string;
  description?: string | null;
  price: number;
  currency: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface CreateProductRequest {
  sku: string;
  name: string;
  description?: string;
  price: number;
  currency: string;
}

export interface UpdateProductRequest {
  name: string;
  description?: string;
  price: number;
  currency: string;
}
