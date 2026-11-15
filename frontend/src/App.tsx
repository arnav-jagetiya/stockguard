import { lazy, Suspense } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import { LoadingSpinner } from './components/common/LoadingSpinner';

// Route-level Code Splitting
const MarketingPage = lazy(() => import('./pages/marketing/MarketingPage').then((m) => ({ default: m.MarketingPage })));
const LoginPage = lazy(() => import('./pages/auth/LoginPage').then((m) => ({ default: m.LoginPage })));
const AppShell = lazy(() => import('./components/layout/AppShell').then((m) => ({ default: m.AppShell })));

// Authenticated Application Modules
const DashboardPage = lazy(() => import('./pages/app/DashboardPage').then((m) => ({ default: m.DashboardPage })));
const ProductsPage = lazy(() => import('./pages/app/ProductsPage').then((m) => ({ default: m.ProductsPage })));
const WarehousesPage = lazy(() => import('./pages/app/WarehousesPage').then((m) => ({ default: m.WarehousesPage })));
const InventoryPage = lazy(() => import('./pages/app/InventoryPage').then((m) => ({ default: m.InventoryPage })));
const OrdersPage = lazy(() => import('./pages/app/OrdersPage').then((m) => ({ default: m.OrdersPage })));
const ReservationsPage = lazy(() => import('./pages/app/ReservationsPage').then((m) => ({ default: m.ReservationsPage })));
const TransfersPage = lazy(() => import('./pages/app/TransfersPage').then((m) => ({ default: m.TransfersPage })));
const ReconciliationPage = lazy(() => import('./pages/app/ReconciliationPage').then((m) => ({ default: m.ReconciliationPage })));
const AuditLogsPage = lazy(() => import('./pages/app/AuditLogsPage').then((m) => ({ default: m.AuditLogsPage })));
const TeamPage = lazy(() => import('./pages/app/TeamPage').then((m) => ({ default: m.TeamPage })));

const RouteLoadingFallback = () => (
  <div className="min-h-screen bg-[#080c14] flex items-center justify-center">
    <LoadingSpinner size="lg" message="Loading StockGuard module..." />
  </div>
);

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Suspense fallback={<RouteLoadingFallback />}>
          <Routes>
            {/* Public Marketing Page */}
            <Route path="/" element={<MarketingPage />} />

            {/* Authentication Screen */}
            <Route path="/login" element={<LoginPage />} />

            {/* Authenticated Operations Console */}
            <Route path="/app" element={<AppShell />}>
              <Route index element={<Navigate to="/app/dashboard" replace />} />
              <Route path="dashboard" element={<DashboardPage />} />
              <Route path="products" element={<ProductsPage />} />
              <Route path="warehouses" element={<WarehousesPage />} />
              <Route path="inventory" element={<InventoryPage />} />
              <Route path="orders" element={<OrdersPage />} />
              <Route path="reservations" element={<ReservationsPage />} />
              <Route path="transfers" element={<TransfersPage />} />
              <Route path="reconciliation" element={<ReconciliationPage />} />
              <Route path="audit-logs" element={<AuditLogsPage />} />
              <Route path="team" element={<TeamPage />} />
            </Route>

            {/* Wildcard / Catch-All */}
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </Suspense>
      </AuthProvider>
    </BrowserRouter>
  );
}
