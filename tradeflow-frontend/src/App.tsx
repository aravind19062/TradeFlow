import { lazy, type ComponentType, type ReactNode } from 'react';
import { BrowserRouter, Navigate, Route, Routes, useLocation } from 'react-router';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { AppLayout } from './components/Layout';
import { Spinner, ToastProvider } from './components/ui';
import { LoginPage, RegisterPage } from './pages/AuthPages';
import { NotFoundPage } from './pages/NotFoundPage';

// Signed-in pages are loaded on demand (they carry the chart library).
const page = <K extends string>(load: () => Promise<Record<K, ComponentType>>, name: K) =>
  lazy(() => load().then((m) => ({ default: m[name] })));
const DashboardPage = page(() => import('./pages/DashboardPage'), 'DashboardPage');
const MarketPage = page(() => import('./pages/MarketPage'), 'MarketPage');
const InstrumentPage = page(() => import('./pages/InstrumentPage'), 'InstrumentPage');
const OrdersPage = page(() => import('./pages/OrdersPage'), 'OrdersPage');
const OrderDetailPage = page(() => import('./pages/OrderDetailPage'), 'OrderDetailPage');
const PortfolioPage = page(() => import('./pages/PortfolioPage'), 'PortfolioPage');
const WatchlistPage = page(() => import('./pages/WatchlistPage'), 'WatchlistPage');
const AssistantPage = page(() => import('./pages/AssistantPage'), 'AssistantPage');
const AdminPage = page(() => import('./pages/AdminPage'), 'AdminPage');

function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();
  if (status === 'loading') return <div className="min-h-dvh pt-32"><Spinner label="Restoring your session…" /></div>;
  if (status === 'anonymous') return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  return <>{children}</>;
}

/** UI-level guard only; the gateway and services enforce ROLE_ADMIN themselves. */
function RequireAdmin({ children }: { children: ReactNode }) {
  const { isAdmin } = useAuth();
  return isAdmin ? <>{children}</> : <Navigate to="/" replace />;
}

function GuestOnly({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  if (status === 'authenticated') return <Navigate to="/" replace />;
  return <>{children}</>;
}

export function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<GuestOnly><LoginPage /></GuestOnly>} />
      <Route path="/register" element={<GuestOnly><RegisterPage /></GuestOnly>} />
      <Route element={<RequireAuth><AppLayout /></RequireAuth>}>
        <Route index element={<DashboardPage />} />
        <Route path="market" element={<MarketPage />} />
        <Route path="market/:symbol" element={<InstrumentPage />} />
        <Route path="orders" element={<OrdersPage />} />
        <Route path="orders/:id" element={<OrderDetailPage />} />
        <Route path="portfolio" element={<PortfolioPage />} />
        <Route path="watchlist" element={<WatchlistPage />} />
        <Route path="assistant" element={<AssistantPage />} />
        <Route path="admin" element={<RequireAdmin><AdminPage /></RequireAdmin>} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <ToastProvider>
          <AppRoutes />
        </ToastProvider>
      </AuthProvider>
    </BrowserRouter>
  );
}
