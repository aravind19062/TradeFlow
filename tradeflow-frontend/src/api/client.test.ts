import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig } from 'axios';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, setUnauthorizedListener, toApiError } from './client';
import type { ProblemDetail } from './types';
import { saveSession } from '../auth/session';

function axiosError(status: number | null, data?: ProblemDetail, headers: Record<string, string> = {}, code?: string,
                    url = '/orders'): AxiosError<ProblemDetail> {
  const config = { url, headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  const response = status == null ? undefined
    : { status, data: data ?? {}, headers, statusText: '', config };
  return new AxiosError<ProblemDetail>('failed', code, config, null, response);
}

afterEach(() => {
  setUnauthorizedListener(null);
  sessionStorage.clear();
});

describe('toApiError', () => {
  it('keeps the ProblemDetail code, detail and field errors', () => {
    const e = toApiError(axiosError(400, {
      status: 400, code: 'VALIDATION_FAILED', detail: 'Request validation failed',
      errors: [{ field: 'email', message: 'must be a well-formed email address' }],
    }));
    expect(e.status).toBe(400);
    expect(e.code).toBe('VALIDATION_FAILED');
    expect(e.message).toBe('Request validation failed');
    expect(e.fieldError('email')).toBe('must be a well-formed email address');
  });

  it('reads Retry-After for login throttling', () => {
    const e = toApiError(axiosError(429, { code: 'TOO_MANY_LOGIN_ATTEMPTS', detail: 'Too many' }, { 'retry-after': '120' }));
    expect(e.retryAfterSeconds).toBe(120);
  });

  it('reports network failures and the gateway fallback clearly', () => {
    expect(toApiError(axiosError(null)).code).toBe('NETWORK_ERROR');
    expect(toApiError(axiosError(null, undefined, {}, 'ECONNABORTED')).code).toBe('TIMEOUT');
    const fallback = toApiError(axiosError(503, { code: 'DEPENDENCY_UNAVAILABLE', detail: 'order-service is unavailable; retry shortly' }));
    expect(fallback.message).toContain('order-service is unavailable');
  });
});

describe('interceptors', () => {
  it('attaches the bearer token from the session', async () => {
    saveSession({ token: 'tok123', expiresAt: Date.now() + 3_600_000 });
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => ({ data: {}, status: 200, statusText: 'OK', headers: {}, config }));
    await http.get('/portfolio/summary', { adapter });
    expect(adapter.mock.calls[0][0].headers.get('Authorization')).toBe('Bearer tok123');
  });

  it('sends no Authorization header without a session', async () => {
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => ({ data: [], status: 200, statusText: 'OK', headers: {}, config }));
    await http.get('/market/quotes', { adapter });
    expect(adapter.mock.calls[0][0].headers.get('Authorization')).toBeUndefined();
  });

  it('ends the session on a 401 from a protected endpoint, not from login', async () => {
    const listener = vi.fn();
    setUnauthorizedListener(listener);
    saveSession({ token: 'tok', expiresAt: Date.now() + 3_600_000 });
    const reject401 = (url: string) => async (config: InternalAxiosRequestConfig) => {
      throw axiosError(401, { code: 'UNAUTHENTICATED' }, {}, undefined, url) as unknown as Error & { config: typeof config };
    };
    await expect(http.post('/auth/login', {}, { adapter: reject401('/auth/login') })).rejects.toMatchObject({ status: 401 });
    expect(listener).not.toHaveBeenCalled();
    await expect(http.get('/orders', { adapter: reject401('/orders') })).rejects.toMatchObject({ code: 'UNAUTHENTICATED' });
    expect(listener).toHaveBeenCalledOnce();
    expect(sessionStorage.length).toBe(0);
  });
});
