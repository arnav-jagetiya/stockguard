import React, { useEffect, useState } from 'react';
import { useAuth } from '../../context/AuthContext';
import {
  listProductsApi,
  createProductApi,
  updateProductApi,
  deactivateProductApi,
} from '../../api/products';
import type { Product } from '../../types/product';
import { Button } from '../../components/common/Button';
import { Input } from '../../components/common/Input';
import { Modal } from '../../components/common/Modal';
import { Badge } from '../../components/common/Badge';
import { Table, TableHead, TableHeader, TableBody, TableRow, TableCell } from '../../components/common/Table';
import { LoadingSpinner } from '../../components/common/LoadingSpinner';
import { EmptyState } from '../../components/common/EmptyState';
import { Package, Plus, Search, Edit2, Ban } from 'lucide-react';

export const ProductsPage: React.FC = () => {
  const { hasPermission } = useAuth();
  const canManage = hasPermission('product:manage');

  const [products, setProducts] = useState<Product[]>([]);
  const [search, setSearch] = useState('');
  const [activeOnly, setActiveOnly] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Modal states
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [editingProduct, setEditingProduct] = useState<Product | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Form states
  const [formSku, setFormSku] = useState('');
  const [formName, setFormName] = useState('');
  const [formDesc, setFormDesc] = useState('');
  const [formPrice, setFormPrice] = useState('0.00');
  const [formCurrency, setFormCurrency] = useState('USD');
  const [formError, setFormError] = useState<string | null>(null);

  const fetchProducts = async () => {
    setIsLoading(true);
    setError(null);
    try {
      const data = await listProductsApi(search || undefined, activeOnly);
      setProducts(data);
    } catch (err: unknown) {
      if (err instanceof Error) setError(err.message);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchProducts();
  }, [search, activeOnly]);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);
    setIsSubmitting(true);
    try {
      await createProductApi({
        sku: formSku.trim(),
        name: formName.trim(),
        description: formDesc.trim() || undefined,
        price: parseFloat(formPrice) || 0,
        currency: formCurrency.toUpperCase(),
      });
      setIsCreateOpen(false);
      resetForm();
      await fetchProducts();
    } catch (err: unknown) {
      if (err instanceof Error) setFormError(err.message);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleUpdate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingProduct) return;
    setFormError(null);
    setIsSubmitting(true);
    try {
      await updateProductApi(editingProduct.id, {
        name: formName.trim(),
        description: formDesc.trim() || undefined,
        price: parseFloat(formPrice) || 0,
        currency: formCurrency.toUpperCase(),
      });
      setEditingProduct(null);
      resetForm();
      await fetchProducts();
    } catch (err: unknown) {
      if (err instanceof Error) setFormError(err.message);
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleDeactivate = async (id: string) => {
    if (!window.confirm('Are you sure you want to deactivate this product?')) return;
    try {
      await deactivateProductApi(id);
      await fetchProducts();
    } catch (err: unknown) {
      if (err instanceof Error) alert(err.message);
    }
  };

  const openEdit = (p: Product) => {
    setEditingProduct(p);
    setFormName(p.name);
    setFormDesc(p.description || '');
    setFormPrice(p.price.toString());
    setFormCurrency(p.currency);
    setFormError(null);
  };

  const resetForm = () => {
    setFormSku('');
    setFormName('');
    setFormDesc('');
    setFormPrice('0.00');
    setFormCurrency('USD');
    setFormError(null);
  };

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h1 className="text-xl sm:text-2xl font-bold text-white tracking-tight">Product Catalog</h1>
          <p className="text-xs text-slate-400 mt-1">
            Maintain normalized SKU catalog items, unit pricing, and status.
          </p>
        </div>

        {canManage && (
          <Button
            onClick={() => {
              resetForm();
              setIsCreateOpen(true);
            }}
            icon={<Plus className="w-4 h-4" />}
          >
            Create Product
          </Button>
        )}
      </div>

      {error && (
        <div className="p-3 bg-rose-500/10 border border-rose-500/20 text-rose-400 text-xs rounded-lg">
          {error}
        </div>
      )}

      {/* Filter Bar */}
      <div className="flex flex-col sm:flex-row items-center justify-between gap-4 bg-[#0f172a] p-3.5 rounded-xl border border-slate-800">
        <div className="relative w-full sm:w-80">
          <Search className="w-4 h-4 text-slate-500 absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            type="text"
            placeholder="Filter by SKU or name..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full bg-[#0a101d] text-xs text-slate-100 rounded-lg pl-9 pr-3.5 py-2 border border-slate-800 outline-none focus:border-cyan-500"
          />
        </div>

        <label className="flex items-center gap-2 text-xs text-slate-400 cursor-pointer select-none self-start sm:self-auto">
          <input
            type="checkbox"
            checked={activeOnly}
            onChange={(e) => setActiveOnly(e.target.checked)}
            className="rounded border-slate-700 bg-slate-900 text-cyan-500 focus:ring-0"
          />
          Show Active Only
        </label>
      </div>

      {/* Product Table */}
      {isLoading ? (
        <LoadingSpinner message="Retrieving catalog records..." />
      ) : products.length === 0 ? (
        <EmptyState
          icon={<Package className="w-8 h-8" />}
          title="No Products Found"
          description={search ? 'No products matched your search filter.' : 'Your product catalog is currently empty.'}
          actionLabel={canManage && !search ? 'Add First Product' : undefined}
          onAction={() => {
            resetForm();
            setIsCreateOpen(true);
          }}
        />
      ) : (
        <Table>
          <TableHead>
            <tr>
              <TableHeader>SKU Code</TableHeader>
              <TableHeader>Product Name</TableHeader>
              <TableHeader>Price / Unit</TableHeader>
              <TableHeader>Status</TableHeader>
              <TableHeader>Created</TableHeader>
              {canManage && <TableHeader className="text-right">Actions</TableHeader>}
            </tr>
          </TableHead>
          <TableBody>
            {products.map((p) => (
              <TableRow key={p.id}>
                <TableCell className="font-mono font-bold text-cyan-400">{p.sku}</TableCell>
                <TableCell>
                  <p className="font-semibold text-white">{p.name}</p>
                  {p.description && <p className="text-[11px] text-slate-400 truncate max-w-sm">{p.description}</p>}
                </TableCell>
                <TableCell className="font-mono font-semibold">
                  {p.currency} {Number(p.price).toFixed(2)}
                </TableCell>
                <TableCell>
                  {p.active ? (
                    <Badge variant="success" dot>ACTIVE</Badge>
                  ) : (
                    <Badge variant="neutral">INACTIVE</Badge>
                  )}
                </TableCell>
                <TableCell className="font-mono text-slate-500">
                  {new Date(p.createdAt).toLocaleDateString()}
                </TableCell>
                {canManage && (
                  <TableCell className="text-right space-x-2">
                    <button
                      onClick={() => openEdit(p)}
                      title="Edit Product"
                      className="p-1.5 rounded-lg bg-slate-900 hover:bg-slate-800 text-slate-400 hover:text-cyan-400 border border-slate-800 transition-colors"
                    >
                      <Edit2 className="w-3.5 h-3.5" />
                    </button>
                    {p.active && (
                      <button
                        onClick={() => handleDeactivate(p.id)}
                        title="Deactivate Product"
                        className="p-1.5 rounded-lg bg-slate-900 hover:bg-rose-500/10 text-slate-400 hover:text-rose-400 border border-slate-800 transition-colors"
                      >
                        <Ban className="w-3.5 h-3.5" />
                      </button>
                    )}
                  </TableCell>
                )}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}

      {/* Create Modal */}
      <Modal
        isOpen={isCreateOpen}
        onClose={() => setIsCreateOpen(false)}
        title="Create Catalog Product"
        description="Add a new SKU to the organization's product catalog."
      >
        <form onSubmit={handleCreate} className="space-y-4">
          {formError && (
            <div className="p-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-rose-400 text-xs">
              {formError}
            </div>
          )}
          <Input
            label="SKU Code"
            placeholder="e.g. SKU-PROD-001"
            value={formSku}
            onChange={(e) => setFormSku(e.target.value)}
            required
          />
          <Input
            label="Product Name"
            placeholder="e.g. Industrial Valve Assembly"
            value={formName}
            onChange={(e) => setFormName(e.target.value)}
            required
          />
          <Input
            label="Description (Optional)"
            placeholder="Specification and physical details"
            value={formDesc}
            onChange={(e) => setFormDesc(e.target.value)}
          />
          <div className="grid grid-cols-2 gap-3">
            <Input
              label="Unit Price"
              type="number"
              step="0.01"
              value={formPrice}
              onChange={(e) => setFormPrice(e.target.value)}
              required
            />
            <Input
              label="Currency"
              placeholder="USD"
              value={formCurrency}
              onChange={(e) => setFormCurrency(e.target.value.toUpperCase())}
              required
            />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="ghost" onClick={() => setIsCreateOpen(false)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={isSubmitting}>
              Create Product
            </Button>
          </div>
        </form>
      </Modal>

      {/* Edit Modal */}
      <Modal
        isOpen={!!editingProduct}
        onClose={() => setEditingProduct(null)}
        title="Update Product"
        description={`Modify properties for ${editingProduct?.sku}`}
      >
        <form onSubmit={handleUpdate} className="space-y-4">
          {formError && (
            <div className="p-2.5 rounded-lg bg-rose-500/10 border border-rose-500/30 text-rose-400 text-xs">
              {formError}
            </div>
          )}
          <Input
            label="Product Name"
            value={formName}
            onChange={(e) => setFormName(e.target.value)}
            required
          />
          <Input
            label="Description (Optional)"
            value={formDesc}
            onChange={(e) => setFormDesc(e.target.value)}
          />
          <div className="grid grid-cols-2 gap-3">
            <Input
              label="Unit Price"
              type="number"
              step="0.01"
              value={formPrice}
              onChange={(e) => setFormPrice(e.target.value)}
              required
            />
            <Input
              label="Currency"
              value={formCurrency}
              onChange={(e) => setFormCurrency(e.target.value.toUpperCase())}
              required
            />
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="ghost" onClick={() => setEditingProduct(null)}>
              Cancel
            </Button>
            <Button type="submit" isLoading={isSubmitting}>
              Save Changes
            </Button>
          </div>
        </form>
      </Modal>
    </div>
  );
};
