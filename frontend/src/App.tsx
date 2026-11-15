import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import { MarketingPage } from './pages/marketing/MarketingPage';
import { LoginPage } from './pages/auth/LoginPage';
import { AppShell } from './components/layout/AppShell';

// App Pages
import { DashboardPage } from './pages/app/DashboardPage';
import { ProductsPage } from './pages/app/ProductsPage';
import { WarehousesPage } from './pages/app/WarehousesPage';
import { InventoryPage } from './pages/app/InventoryPage';
import { OrdersPage } from './pages/app/OrdersPage';
import { ReservationsPage } from './pages/app/ReservationsPage';
import { TransfersPage } from './pages/app/TransfersPage';
import { ReconciliationPage } from './pages/app/ReconciliationPage';
import { AuditLogsPage } from './pages/app/AuditLogsPage';
import { TeamPage } from './pages/app/TeamPage';

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
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
      </AuthProvider>
    </BrowserRouter>
  );
}
