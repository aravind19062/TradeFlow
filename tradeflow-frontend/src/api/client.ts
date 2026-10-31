import axios, { AxiosError, type AxiosRequestConfig } from 'axios';
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

/** The auth layer registers here to be told when the backend rejects our token. */
export function setUnauthorizedListener(listener: UnauthorizedListener | null): void {
  onUnauthorized = listener;
}

const PUBLIC_AUTH_PATHS = ['/auth/login', '/auth/register'];

http.interceptors.request.use((config) => {
  const token = currentToken();
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`);
  }
  return config;
});

http.interceptors.response.use(
  (response) => response,
  (error: AxiosError<ProblemDetail>) => {
    const apiError = toApiError(error);
    const path = error.config?.url ?? '';
    // A 401 on a protected call means the token is missing, expired or no longer valid.
    // (A 401 from login itself just means wrong credentials.)
    if (apiError.status === 401 && !PUBLIC_AUTH_PATHS.some((p) => path.startsWith(p))) {
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
