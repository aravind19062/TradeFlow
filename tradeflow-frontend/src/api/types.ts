// Response and request shapes, copied field-for-field from the backend DTOs.
// BigDecimal values arrive as JSON numbers; Instants as ISO-8601 strings.

// ---- user-service: dto/AuthDtos.java, dto/RegisterRequest.java
export type Role = 'USER' | 'ADMIN';

export interface UserResponse {
  id: number;
  name: string;
  email: string;
  role: Role;
  dateOfBirth: string | null;
  gender: string | null;
  mobileNumber: string | null;
  occupation: string | null;
  maritalStatus: string | null;
  incomeRange: string | null;
  active: boolean;
  createdAt: string;
}

export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: UserResponse;
}

/** POST /api/auth/login: tokens, or an MFA challenge for accounts with two-factor authentication. */
export interface LoginResponse {
  mfaRequired: boolean;
  accessToken?: string;
  tokenType?: string;
  expiresInSeconds?: number;
  user?: UserResponse;
  mfaToken?: string;
  mfaExpiresInSeconds?: number;
}

export interface StepUpResponse {
  stepUpToken: string;
  tokenType: string;
  expiresInSeconds: number;
}

export interface MfaStatus {
  enabled: boolean;
  enabledAt: string | null;
  recoveryCodesRemaining: number;
  enrollmentPending: boolean;
}

export interface MfaEnrollment {
  secret: string;
  otpauthUri: string;
}

export interface SecurityEventView {
  type: string;
  detail: string | null;
  clientIp: string | null;
  userAgent: string | null;
  occurredAt: string;
  byAdministrator: boolean;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface RegisterRequest {
  name: string;
  email: string;
  password: string;
  dateOfBirth?: string;
  gender?: string;
  mobileNumber?: string;
  occupation?: string;
  maritalStatus?: string;
  incomeRange?: string;
}

// ---- market-data-service: web/MarketDtos.java, domain/PriceAlert.java
export interface InstrumentResponse {
  symbol: string;
  name: string;
  sector: string;
  assetClass: string;
  currency: string;
}

export interface QuoteResponse extends InstrumentResponse {
  price: number;
  previousPrice: number | null;
  change: number | null;
  changeRatio: number | null;
  tick: number;
  asOf: string;
  source: string;
}

export interface PricePoint {
  tick: number;
  price: number;
  asOf: string;
}

export interface HistoryResponse {
  symbol: string;
  source: string;
  points: PricePoint[];
}

export interface SimulationResponse {
  currentTick: number;
  source: string;
  note: string;
}

export type AlertDirection = 'ABOVE' | 'BELOW';
export type AlertStatus = 'ACTIVE' | 'TRIGGERED' | 'CANCELLED';

export interface AlertRequest {
  symbol: string;
  direction: AlertDirection;
  threshold: number;
  note?: string;
}

export interface AlertResponse {
  id: string;
  symbol: string;
  direction: AlertDirection;
  threshold: number;
  status: AlertStatus;
  note: string | null;
  createdAt: string;
  triggeredAt: string | null;
  triggeredPrice: number | null;
}

// ---- order-service: web/OrderDtos.java, domain/OrderStatus.java, common Events.OrderSide/OrderType
export type OrderSide = 'BUY' | 'SELL';
export type OrderType = 'MARKET' | 'LIMIT';
export type OrderStatus = 'PENDING' | 'OPEN' | 'FILLED' | 'REJECTED' | 'CANCELLED' | 'EXPIRED';

export const TERMINAL_STATUSES: readonly OrderStatus[] = ['FILLED', 'REJECTED', 'CANCELLED', 'EXPIRED'];

export interface PlaceOrderRequest {
  symbol: string;
  side: OrderSide;
  type: OrderType;
  quantity: number;
  limitPrice?: number;
  clientOrderId?: string;
}

export interface OrderResponse {
  id: string;
  clientOrderId: string | null;
  symbol: string;
  side: OrderSide;
  type: OrderType;
  quantity: number;
  limitPrice: number | null;
  reservationPrice: number;
  status: OrderStatus;
  rejectCode: string | null;
  statusReason: string | null;
  filledQuantity: number;
  averageFillPrice: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface StatusChangeResponse {
  from: OrderStatus | null;
  to: OrderStatus;
  reason: string | null;
  at: string;
}

export interface TradeResponse {
  id: string;
  orderId: string;
  symbol: string;
  side: OrderSide;
  quantity: number;
  price: number;
  grossAmount: number;
  executedAt: string;
}

export interface OrderDetailResponse {
  order: OrderResponse;
  history: StatusChangeResponse[];
  trades: TradeResponse[];
}

// ---- common: web/PageResponse.java
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// ---- portfolio-service: web/PortfolioViews.java, domain/LedgerEntry.java
export interface HoldingView {
  symbol: string;
  name: string;
  sector: string;
  assetClass: string;
  quantity: number;
  reservedQuantity: number;
  averageCost: number;
  costBasis: number;
  marketPrice: number;
  marketValue: number;
  unrealizedPnl: number;
  unrealizedPnlRatio: number | null;
  realizedPnl: number;
  weight: number | null;
}

export interface PortfolioSummary {
  userId: number;
  currency: string;
  cashBalance: number;
  reservedCash: number;
  availableCash: number;
  holdingsMarketValue: number;
  holdingsCostBasis: number;
  totalValue: number;
  netDeposits: number;
  realizedPnl: number;
  unrealizedPnl: number;
  totalPnl: number;
  totalReturnRatio: number | null;
  holdings: HoldingView[];
  pricesAvailable: boolean;
  pricesAsOf: string | null;
  priceSource: string;
  notes: string[];
}

export interface AllocationSlice {
  name: string;
  value: number;
  weight: number;
}

export interface RiskReport {
  totalValue: number;
  cashWeight: number;
  investedWeight: number;
  largestPosition: string | null;
  largestPositionWeight: number | null;
  herfindahlIndex: number | null;
  effectiveNumberOfHoldings: number | null;
  bySymbol: AllocationSlice[];
  bySector: AllocationSlice[];
  byAssetClass: AllocationSlice[];
  maxDrawdownRatio: number | null;
  warnings: string[];
  methodology: string[];
  pricesAvailable: boolean;
}

export interface PerformancePoint {
  at: string;
  cash: number;
  holdingsValue: number;
  totalValue: number;
}

export interface PerformanceReport {
  from: string | null;
  to: string | null;
  startValue: number;
  endValue: number;
  change: number;
  changeRatio: number | null;
  peakValue: number;
  maxDrawdownRatio: number | null;
  pointCount: number;
  points: PerformancePoint[];
  priceSource: string;
  notes: string[];
}

export type LedgerType = 'DEPOSIT' | 'BUY' | 'SELL';

export interface LedgerEntryView {
  id: number;
  type: LedgerType;
  symbol: string | null;
  quantity: number | null;
  price: number | null;
  cashDelta: number;
  cashBalanceAfter: number;
  realizedPnl: number | null;
  tradeId: string | null;
  orderId: string | null;
  occurredAt: string;
}

export interface ReconciliationIssue {
  userId: number;
  check: string;
  expected: string;
  actual: string;
}

export interface ReconciliationReport {
  checkedAt: string;
  accountsChecked: number;
  consistent: boolean;
  issues: ReconciliationIssue[];
}

// ---- portfolio-service: assistant/AssistantModels.java, AssistantService.capabilities()
export type AssistantMode = 'LLM' | 'DETERMINISTIC' | 'REFUSED';

export interface AssistantFact {
  tool: string;
  input: Record<string, unknown>;
  kind: string;
  data: unknown;
}

export interface AskResponse {
  question: string;
  mode: AssistantMode;
  answerKind: string;
  answer: string;
  provider: string | null;
  model: string | null;
  facts: AssistantFact[];
  notice: string | null;
  disclaimers: string[];
}

export interface AssistantCapabilities {
  provider: string;
  model: string;
  llmEnabled: boolean;
  tools: string[];
  readOnly: boolean;
  examples: string[];
}

// ---- RFC 9457 problem body from GlobalExceptionHandler / gateway
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  code?: string;
  errors?: { field: string; message: string }[];
  service?: string;
}
