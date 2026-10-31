// Response and request shapes, copied field-for-field from the backend DTOs.
// BigDecimal values arrive as JSON numbers; Instants as ISO-8601 strings.

// ---- user-service: dto/AuthDtos.java, dto/RegisterRequest.java
export type Role = 'USER' | 'ADMIN' | 'READINESS_REVIEWER';

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
  /** Mid price. Market buys fill at `ask`, market sells at `bid` (simulated spread). */
  price: number;
  bid: number;
  ask: number;
  spread: number;
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
/** STOP and STOP_LIMIT are SELL-only in the demo (long-only): they protect holdings. */
export type OrderType = 'MARKET' | 'LIMIT' | 'STOP' | 'STOP_LIMIT';
export type OrderStatus = 'PENDING' | 'OPEN' | 'FILLED' | 'REJECTED' | 'CANCELLED' | 'EXPIRED';

export const TERMINAL_STATUSES: readonly OrderStatus[] = ['FILLED', 'REJECTED', 'CANCELLED', 'EXPIRED'];

export interface PlaceOrderRequest {
  symbol: string;
  side: OrderSide;
  type: OrderType;
  quantity: number;
  limitPrice?: number;
  triggerPrice?: number;
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
  triggerPrice: number | null;
  triggeredAt: string | null;
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

// ---- learning-service: academy/AcademyService.java, academy/QuizService.java
export type ModuleStatus = 'NOT_STARTED' | 'IN_PROGRESS' | 'QUIZ_AVAILABLE' | 'COMPLETED';

export interface LessonSummary { slug: string; title: string; completed: boolean }

export interface ModuleSummary {
  slug: string;
  title: string;
  summary: string;
  position: number;
  mandatory: boolean;
  lessonsCompleted: number;
  lessonCount: number;
  bestScorePct: number | null;
  quizPassed: boolean;
  status: ModuleStatus;
  lessons: LessonSummary[];
  quizQuestions: number;
  quizTimeLimitMinutes: number;
}

export interface AcademyOverview {
  contentVersion: number;
  policyVersion: number;
  modulePassMarkPct: number;
  modulesCompleted: number;
  moduleCount: number;
  modules: ModuleSummary[];
}

export interface LessonView {
  moduleSlug: string;
  moduleTitle: string;
  slug: string;
  title: string;
  body: string;
  completed: boolean;
  previousLesson: string | null;
  nextLesson: string | null;
}

export interface ServedQuestion {
  key: string;
  position: number;
  prompt: string;
  multi: boolean;
  options: { key: string; text: string }[];
}

export interface QuizAttemptView {
  id: string;
  quizKey: string;
  startedAt: string;
  expiresAt: string;
  passMarkPct: number;
  questions: ServedQuestion[];
}

export interface GradedQuestion {
  key: string;
  prompt: string;
  multi: boolean;
  options: { key: string; text: string; correct: boolean; selected: boolean }[];
  correct: boolean;
  explanation: string;
}

export interface QuizResultView {
  id: string;
  quizKey: string;
  startedAt: string;
  submittedAt: string;
  questionCount: number;
  correctCount: number;
  scorePct: number;
  passMarkPct: number;
  passed: boolean;
  policyVersion: number | null;
  questions: GradedQuestion[];
}

// ---- learning-service: readiness/ReadinessService.java, readiness/ExerciseService.java
export type ReadinessState = 'NOT_STARTED' | 'IN_PROGRESS' | 'ASSESSMENT_PENDING' | 'REVIEW_REQUIRED'
  | 'ACKNOWLEDGEMENT_PENDING' | 'QUALIFIED' | 'ACCESS_REVOKED';

export interface ExerciseView {
  slug: string;
  title: string;
  module: string;
  description: string;
  required: boolean;
  startedAt: string | null;
  completedAt: string | null;
}

export interface FinalAssessmentView {
  available: boolean;
  failedAttempts: number;
  maxFailedAttempts: number;
  retakeAvailableAt: string | null;
  lastScorePct: number | null;
  passed: boolean;
  passMarkPct: number;
  questions: number;
  timeLimitMinutes: number;
}

export interface ReadinessStatusView {
  state: ReadinessState;
  reason: string;
  policyVersion: number;
  qualifiedAt: string | null;
  expiresAt: string | null;
  selfExcludedUntil: string | null;
  eligibleForLiveTrading: boolean;
  nextStep: string;
  checklist: {
    modulesCompleted: number;
    moduleCount: number;
    modulesDone: boolean;
    remediationModules: string[];
    exercises: ExerciseView[];
    exercisesDone: boolean;
    finalAssessment: FinalAssessmentView;
    acknowledged: boolean;
  };
  policy: {
    version: number;
    modulePassMarkPct: number;
    finalPassMarkPct: number;
    finalRetakeCooldownHours: number;
    maxFailedFinalAttempts: number;
    qualificationValidityDays: number;
  };
}

export interface ReadinessHistoryItem {
  seq: number;
  fromState: string | null;
  toState: string;
  reasonCode: string;
  actorType: string;
  policyVersion: number;
  occurredAt: string;
}

export interface AcknowledgementView { textVersion: number; text: string; textSha256: string; phrase: string }

export interface SizingScenario {
  id: string;
  symbol: string;
  accountValue: number;
  riskPct: number;
  entryPrice: number;
  stopPrice: number;
  expiresAt: string;
  answeredCorrectly: boolean;
  requiredQuantity: number | null;
  answerAttempts: number;
}

export interface ReviewCase {
  userId: number;
  state: ReadinessState;
  failedAttempts: number;
  scores: number[];
  cycleStartedAt: string | null;
  weakModules: string[];
}

export interface AdminUserReadiness {
  status: ReadinessStatusView;
  history: ReadinessHistoryItem[];
  chain: { userId: number; events: number; intact: boolean; firstBrokenSeq: number | null };
}
