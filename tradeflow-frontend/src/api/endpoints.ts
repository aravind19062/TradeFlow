import { del, get, post, put } from './client';
import type {
  AcknowledgementView, AdminUserReadiness, ReadinessHistoryItem, ReadinessStatusView, ReviewCase, SizingScenario,
  AcademyOverview, LessonView, ModuleSummary, QuizAttemptView, QuizResultView,
  AlertRequest, AlertResponse, AlertStatus, AskResponse, AssistantCapabilities, HistoryResponse,
  InstrumentResponse, LedgerEntryView, LedgerType, LoginRequest, LoginResponse, MfaEnrollment, MfaStatus,
  SecurityEventView, StepUpResponse, OrderDetailResponse, OrderResponse, OrderSide,
  OrderStatus, PageResponse, PerformanceReport, PlaceOrderRequest, PortfolioSummary, QuoteResponse,
  ReconciliationReport, RegisterRequest, RiskReport, SimulationResponse, TokenResponse, TradeResponse, UserResponse,
} from './types';

// Every path below exists in a backend controller and is routed by the API gateway
// (tradeflow-api-gateway/src/main/resources/application.properties). Simulated-trading calls
// use the gateway's explicit /api/demo/** routes, whose responses are labelled DEMO.

const enc = encodeURIComponent;

/** Drops undefined/empty values so optional filters are simply not sent. */
function params(p: Record<string, string | number | undefined | null>) {
  return Object.fromEntries(Object.entries(p).filter(([, v]) => v !== undefined && v !== null && v !== ''));
}

// user-service: AuthController
export const authApi = {
  login: (body: LoginRequest) => post<LoginResponse>('/auth/login', body),
  loginMfa: (mfaToken: string, factor: { code?: string; recoveryCode?: string }) =>
    post<TokenResponse>('/auth/login/mfa', { mfaToken, ...factor }),
  register: (body: RegisterRequest) => post<TokenResponse>('/auth/register', body),
  /** Uses the HttpOnly refresh cookie; the new refresh token comes back as a cookie too. */
  refresh: () => post<TokenResponse>('/auth/refresh'),
  logout: () => post<void>('/auth/logout'),
  logoutAll: () => post<void>('/auth/logout-all'),
  stepUp: (password: string, code: string) => post<StepUpResponse>('/auth/step-up', { password, code }),
  me: () => get<UserResponse>('/users/me'),
};

// user-service: MfaController
export const mfaApi = {
  status: () => get<MfaStatus>('/auth/mfa'),
  enroll: (password: string) => post<MfaEnrollment>('/auth/mfa/totp/enroll', { password }),
  confirm: (code: string) => post<{ recoveryCodes: string[] }>('/auth/mfa/totp/confirm', { code }),
  regenerateRecoveryCodes: (password: string, code: string) =>
    post<{ recoveryCodes: string[] }>('/auth/mfa/recovery-codes', { password, code }),
  disable: (password: string, factor: { code?: string; recoveryCode?: string }) =>
    post<void>('/auth/mfa/totp/disable', { password, ...factor }),
  securityEvents: () => get<SecurityEventView[]>('/auth/mfa/security-events'),
};

// market-data-service: MarketDataController, SimulationController (public GETs)
export const marketApi = {
  instruments: () => get<InstrumentResponse[]>('/demo/market/instruments'),
  quotes: (symbols?: string[]) =>
    get<QuoteResponse[]>('/demo/market/quotes', { params: params({ symbols: symbols?.join(',') }) }),
  quote: (symbol: string) => get<QuoteResponse>(`/demo/market/quotes/${enc(symbol)}`),
  history: (symbol: string, limit = 200) =>
    get<HistoryResponse>(`/demo/market/history/${enc(symbol)}`, { params: { limit } }),
  simulation: () => get<SimulationResponse>('/demo/market/simulation'),
  /** ADMIN only. */
  advance: (ticks: number) => post<SimulationResponse>('/demo/market/simulation/advance', undefined, { params: { ticks } }),
};

// market-data-service: WatchlistController
export const watchlistApi = {
  list: () => get<QuoteResponse[]>('/demo/watchlist'),
  add: (symbol: string) => put<QuoteResponse[]>(`/demo/watchlist/${enc(symbol)}`),
  remove: (symbol: string) => del<void>(`/demo/watchlist/${enc(symbol)}`),
};

// market-data-service: AlertController
export const alertApi = {
  list: (status?: AlertStatus) => get<AlertResponse[]>('/demo/alerts', { params: params({ status }) }),
  create: (body: AlertRequest) => post<AlertResponse>('/demo/alerts', body),
  cancel: (id: string) => del<AlertResponse>(`/demo/alerts/${enc(id)}`),
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
  place: (body: PlaceOrderRequest) => post<OrderResponse>('/demo/orders', body),
  get: (id: string) => get<OrderDetailResponse>(`/demo/orders/${enc(id)}`),
  list: (f: OrderFilters = {}) =>
    get<PageResponse<OrderResponse>>('/demo/orders', { params: params({ ...f }) }),
  cancel: (id: string) => post<OrderResponse>(`/demo/orders/${enc(id)}/cancel`),
  trades: (f: { symbol?: string; page?: number; size?: number } = {}) =>
    get<PageResponse<TradeResponse>>('/demo/trades', { params: params({ ...f }) }),
};

// portfolio-service: PortfolioController
export const portfolioApi = {
  summary: () => get<PortfolioSummary>('/demo/portfolio/summary'),
  performance: () => get<PerformanceReport>('/demo/portfolio/performance'),
  risk: () => get<RiskReport>('/demo/portfolio/risk'),
  transactions: (f: { type?: LedgerType; page?: number; size?: number } = {}) =>
    get<PageResponse<LedgerEntryView>>('/demo/portfolio/transactions', { params: params({ ...f }) }),
  reconciliation: () => get<ReconciliationReport>('/demo/portfolio/reconciliation'),
};

// portfolio-service: AssistantController
export const assistantApi = {
  ask: (question: string) => post<AskResponse>('/demo/assistant/ask', { question }),
  capabilities: () => get<AssistantCapabilities>('/demo/assistant/capabilities'),
};

// portfolio-service: AdminController (ADMIN only)
export const adminApi = {
  reconcileAll: () => get<ReconciliationReport>('/admin/reconciliation'),
};

// learning-service: AcademyController
export const academyApi = {
  overview: () => get<AcademyOverview>('/academy/modules'),
  module: (slug: string) => get<ModuleSummary>(`/academy/modules/${enc(slug)}`),
  lesson: (module: string, lesson: string) => get<LessonView>(`/academy/modules/${enc(module)}/lessons/${enc(lesson)}`),
  completeLesson: (module: string, lesson: string) =>
    post<void>(`/academy/modules/${enc(module)}/lessons/${enc(lesson)}/complete`),
  startQuiz: (module: string) => post<QuizAttemptView>(`/academy/modules/${enc(module)}/quiz/attempts`),
  submit: (attemptId: string, answers: { questionKey: string; optionKeys: string[] }[]) =>
    post<QuizResultView>(`/academy/attempts/${enc(attemptId)}/submit`, { answers }),
};

// learning-service: ReadinessController
export const readinessApi = {
  status: () => get<ReadinessStatusView>('/readiness'),
  startAssessment: () => post<QuizAttemptView>('/readiness/assessment/attempts'),
  acknowledgement: () => get<AcknowledgementView>('/readiness/acknowledgement'),
  acknowledge: (textVersion: number, typedPhrase: string) =>
    post<ReadinessStatusView>('/readiness/acknowledgement', { textVersion, typedPhrase }),
  selfExclude: (days: number) => post<ReadinessStatusView>('/readiness/self-exclusion', { days }),
  history: () => get<ReadinessHistoryItem[]>('/readiness/history'),
  startExercise: (slug: string) => post<void>(`/readiness/exercises/${enc(slug)}/start`),
  scenario: () => post<SizingScenario>('/readiness/exercises/position-sizing/scenario'),
  answer: (quantity: number) =>
    post<{ correct: boolean; explanation: string; scenario: SizingScenario }>('/readiness/exercises/position-sizing/answer', { quantity }),
};

// learning-service: ReviewController (READINESS_REVIEWER or ADMIN)
export const reviewApi = {
  queue: () => get<ReviewCase[]>('/admin/readiness/reviews'),
  decide: (userId: number, decision: 'ALLOW_RETAKE' | 'REQUIRE_REMEDIATION', notes: string, remediationModules: string[]) =>
    post<ReadinessStatusView>(`/admin/readiness/reviews/${userId}/decision`, { decision, notes, remediationModules }),
};

// learning-service: AdminReadinessController (ADMIN)
export const adminReadinessApi = {
  user: (userId: number) => get<AdminUserReadiness>(`/admin/readiness/users/${userId}`),
  revoke: (userId: number, reason: string) => post<ReadinessStatusView>(`/admin/readiness/users/${userId}/revoke`, { reason }),
  reinstate: (userId: number, reason: string) => post<ReadinessStatusView>(`/admin/readiness/users/${userId}/reinstate`, { reason }),
};
