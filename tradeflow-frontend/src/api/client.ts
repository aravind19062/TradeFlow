import axios, { AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios';
import { clearSession, currentToken } from '../auth/session';
import type { ProblemDetail } from './types';

/**
 * Single HTTP client for the whole app. Base URL is same-origin "/api": in development
 * Vite proxies it to the API gateway, in Docker nginx does. The browser never calls a
 * microservice directly.
 */
export const http = axios.create({
  baseURL: '/api',
  timeout: 70_000, // the gateway allows 65 s for the slowest route (AI assistant)
  headers: { Accept: 'application/json' },
});

/** Normalised error for every failed call; `code` is the backend's stable ProblemDetail code. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly fieldErrors: { field: string; message: string }[];
  readonly retryAfterSeconds: number | null;

  constructor(status: number, code: string, message: string,
              fieldErrors: { field: string; message: string }[] = [], retryAfterSeconds: number | null = null) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
    this.retryAfterSeconds = retryAfterSeconds;
  }

  fieldError(field: string): string | undefined {
    return this.fieldErrors.find((e) => e.field === field)?.message;
  }
}

type UnauthorizedListener = () => void;
let onUnauthorized: UnauthorizedListener | null = null;
let refreshHandler: (() => Promise<boolean>) | null = null;
let refreshInFlight: Promise<boolean> | null = null;

/** The auth layer registers here to be told when the session cannot be continued. */
export function setUnauthorizedListener(listener: UnauthorizedListener | null): void {
  onUnauthorized = listener;
}

/** The auth layer registers how to obtain a new access token from the refresh cookie. */
export function setRefreshHandler(handler: (() => Promise<boolean>) | null): void {
  refreshHandler = handler;
}

/** One refresh at a time, however many requests hit a 401 together. */
export function refreshOnce(): Promise<boolean> {
  if (!refreshHandler) return Promise.resolve(false);
  refreshInFlight ??= refreshHandler().finally(() => { refreshInFlight = null; });
  return refreshInFlight;
}

// A 401 from these means wrong credentials (or no session yet), not an expired access token, so it is
// never answered with a refresh-and-retry. Step-up re-checks the password and a TOTP code.
const AUTH_PATHS = ['/auth/passkeys/login', '/auth/passkeys/login/options', '/auth/login', '/auth/register', '/auth/refresh', '/auth/logout', '/auth/login/mfa', '/auth/step-up'];
const isAuthPath = (url?: string) => !!url && AUTH_PATHS.some((p) => url.startsWith(p));

type RetriableConfig = InternalAxiosRequestConfig & { _retried?: boolean };

http.interceptors.request.use((config) => {
  // Lets the backend refuse cookie-authenticated calls that did not come from this app (CSRF).
  config.headers.set('X-TradeFlow-Client', 'web');
  if (!config.headers.has('Authorization')) {
    const token = currentToken();
    if (token) config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

/**
 * Which trading environment a path belongs to, by its first segment. The gateway labels every
 * DEMO and LIVE response with X-TradeFlow-Environment; a response that is missing the label or
 * carries the other one is refused, so simulated data can never be rendered as live (or the reverse).
 */
export function expectedEnvironment(url: string | undefined): 'DEMO' | 'LIVE' | null {
  if (!url) return null;
  if (url.startsWith('/demo/')) return 'DEMO';
  if (url.startsWith('/live/') || url.startsWith('/admin/live/')) return 'LIVE';
  return null;
}

http.interceptors.response.use(
  (response) => {
    const expected = expectedEnvironment(response.config.url);
    const actual = response.headers?.['x-tradeflow-environment'];
    if (expected && actual !== expected) {
      return Promise.reject(new ApiError(response.status, 'ENVIRONMENT_MISMATCH',
        `Refused a response labelled ${actual ?? 'with no environment'} for a ${expected} request.`));
    }
    return response;
  },
  async (error: AxiosError<ProblemDetail>) => {
    const apiError = toApiError(error);
    const config = error.config as RetriableConfig | undefined;
    // A 401 on a protected call: the access token expired or was revoked. Try the refresh
    // cookie once, then repeat the request; if that fails the session is over.
    if (apiError.status === 401 && config && !isAuthPath(config.url) && !config._retried
        && config.headers?.get('X-TradeFlow-Step-Up') == null) {
      config._retried = true;
      if (await refreshOnce()) {
        config.headers.delete('Authorization');
        return http.request(config);
      }
      clearSession();
      onUnauthorized?.();
    }
    return Promise.reject(apiError);
  },
);

export function toApiError(error: AxiosError<ProblemDetail>): ApiError {
  if (!error.response) {
    if (error.code === 'ECONNABORTED') {
      return new ApiError(0, 'TIMEOUT', 'The server took too long to respond. Please try again.');
    }
    return new ApiError(0, 'NETWORK_ERROR', 'Cannot reach TradeFlow. Check that the backend is running.');
  }
  const { status, data, headers } = error.response;
  const body: ProblemDetail = data && typeof data === 'object' ? data : {};
  const retryAfterHeader = headers?.['retry-after'];
  const retryAfter = retryAfterHeader != null && !Number.isNaN(Number(retryAfterHeader))
    ? Number(retryAfterHeader) : null;
  const code = body.code ?? (status >= 500 ? 'SERVER_ERROR' : `HTTP_${status}`);
  return new ApiError(status, code, body.detail ?? defaultMessage(status), body.errors ?? [], retryAfter);
}

function defaultMessage(status: number): string {
  if (status === 401) return 'Please sign in again.';
  if (status === 403) return 'You are not allowed to perform this action.';
  if (status === 404) return 'Not found.';
  if (status === 503) return 'The service is temporarily unavailable. Please retry shortly.';
  if (status >= 500) return 'Unexpected server error.';
  return `Request failed (${status}).`;
}

export async function get<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return (await http.get<T>(url, config)).data;
}

export async function post<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return (await http.post<T>(url, body, config)).data;
}

export async function put<T>(url: string, body?: unknown, config?: AxiosRequestConfig): Promise<T> {
  return (await http.put<T>(url, body, config)).data;
}

export async function del<T>(url: string, config?: AxiosRequestConfig): Promise<T> {
  return (await http.delete<T>(url, config)).data;
}
