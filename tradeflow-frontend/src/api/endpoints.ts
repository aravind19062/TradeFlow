import { del, get, post, put } from './client';
import type {
  AlertRequest, AlertResponse, AlertStatus, AskResponse, AssistantCapabilities, HistoryResponse,
  InstrumentResponse, LedgerEntryView, LedgerType, LoginRequest, OrderDetailResponse, OrderResponse, OrderSide,
  OrderStatus, PageResponse, PerformanceReport, PlaceOrderRequest, PortfolioSummary, QuoteResponse,
  ReconciliationReport, RegisterRequest, RiskReport, SimulationResponse, TokenResponse, TradeResponse, UserResponse,
} from './types';

// Every path below exists in a backend controller and is routed by the API gateway
// (tradeflow-api-gateway/src/main/resources/application.properties).

const enc = encodeURIComponent;

/** Drops undefined/empty values so optional filters are simply not sent. */
function params(p: Record<string, string | number | undefined | null>) {
  return Object.fromEntries(Object.entries(p).filter(([, v]) => v !== undefined && v !== null && v !== ''));
}

// user-service: AuthController
export const authApi = {
  login: (body: LoginRequest) => post<TokenResponse>('/auth/login', body),
  register: (body: RegisterRequest) => post<TokenResponse>('/auth/register', body),
  me: () => get<UserResponse>('/users/me'),
};

// market-data-service: MarketDataController, SimulationController (public GETs)
export const marketApi = {
  instruments: () => get<InstrumentResponse[]>('/market/instruments'),
  quotes: (symbols?: string[]) =>
    get<QuoteResponse[]>('/market/quotes', { params: params({ symbols: symbols?.join(',') }) }),
  quote: (symbol: string) => get<QuoteResponse>(`/market/quotes/${enc(symbol)}`),
  history: (symbol: string, limit = 200) =>
    get<HistoryResponse>(`/market/history/${enc(symbol)}`, { params: { limit } }),
  simulation: () => get<SimulationResponse>('/market/simulation'),
  /** ADMIN only. */
  advance: (ticks: number) => post<SimulationResponse>('/market/simulation/advance', undefined, { params: { ticks } }),
};

// market-data-service: WatchlistController
export const watchlistApi = {
  list: () => get<QuoteResponse[]>('/watchlist'),
  add: (symbol: string) => put<QuoteResponse[]>(`/watchlist/${enc(symbol)}`),
  remove: (symbol: string) => del<void>(`/watchlist/${enc(symbol)}`),
};

// market-data-service: AlertController
export const alertApi = {
  list: (status?: AlertStatus) => get<AlertResponse[]>('/alerts', { params: params({ status }) }),
  create: (body: AlertRequest) => post<AlertResponse>('/alerts', body),
  cancel: (id: string) => del<AlertResponse>(`/alerts/${enc(id)}`),
};

export interface OrderFilters {
  status?: OrderStatus;
  symbol?: string;
  side?: OrderSide;
  page?: number;
  size?: number;
}

// order-service: OrderController
export const orderApi = {
  place: (body: PlaceOrderRequest) => post<OrderResponse>('/orders', body),
  get: (id: string) => get<OrderDetailResponse>(`/orders/${enc(id)}`),
  list: (f: OrderFilters = {}) =>
    get<PageResponse<OrderResponse>>('/orders', { params: params({ ...f }) }),
  cancel: (id: string) => post<OrderResponse>(`/orders/${enc(id)}/cancel`),
  trades: (f: { symbol?: string; page?: number; size?: number } = {}) =>
    get<PageResponse<TradeResponse>>('/trades', { params: params({ ...f }) }),
};

// portfolio-service: PortfolioController
export const portfolioApi = {
  summary: () => get<PortfolioSummary>('/portfolio/summary'),
  performance: () => get<PerformanceReport>('/portfolio/performance'),
  risk: () => get<RiskReport>('/portfolio/risk'),
  transactions: (f: { type?: LedgerType; page?: number; size?: number } = {}) =>
    get<PageResponse<LedgerEntryView>>('/portfolio/transactions', { params: params({ ...f }) }),
  reconciliation: () => get<ReconciliationReport>('/portfolio/reconciliation'),
};

// portfolio-service: AssistantController
export const assistantApi = {
  ask: (question: string) => post<AskResponse>('/assistant/ask', { question }),
  capabilities: () => get<AssistantCapabilities>('/assistant/capabilities'),
};

// portfolio-service: AdminController (ADMIN only)
export const adminApi = {
  reconcileAll: () => get<ReconciliationReport>('/admin/reconciliation'),
};
