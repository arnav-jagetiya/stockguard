import { apiClient } from './client';
import type { CreateProductRequest, Product, UpdateProductRequest } from '../types/product';

export async function getProductsApi(search?: string, activeOnly: boolean = false): Promise<Product[]> {
  const q = new URLSearchParams();
  if (search) q.append('search', search);
  if (activeOnly) q.append('activeOnly', 'true');
  const qs = q.toString() ? `?${q.toString()}` : '';
  return apiClient<Product[]>(`/api/products${qs}`);
}

export const listProductsApi = getProductsApi;

export async function getProductByIdApi(productId: string): Promise<Product> {
  return apiClient<Product>(`/api/products/${productId}`);
}

export async function createProductApi(data: CreateProductRequest): Promise<Product> {
  return apiClient<Product>('/api/products', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function updateProductApi(productId: string, data: UpdateProductRequest): Promise<Product> {
  return apiClient<Product>(`/api/products/${productId}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

export async function deactivateProductApi(productId: string): Promise<Product> {
  return apiClient<Product>(`/api/products/${productId}`, {
    method: 'DELETE',
  });
}
