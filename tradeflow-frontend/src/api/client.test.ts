import { AxiosError, AxiosHeaders, type InternalAxiosRequestConfig } from 'axios';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { http, setRefreshHandler, setUnauthorizedListener, toApiError } from './client';
import type { ProblemDetail } from './types';
import { clearSession, currentToken, setSession } from '../auth/session';

function axiosError(status: number | null, data?: ProblemDetail, headers: Record<string, string> = {}, code?: string,
                    url = '/orders'): AxiosError<ProblemDetail> {
  const config = { url, headers: new AxiosHeaders() } as InternalAxiosRequestConfig;
  const response = status == null ? undefined
    : { status, data: data ?? {}, headers, statusText: '', config };
  return new AxiosError<ProblemDetail>('failed', code, config, null, response);
}

afterEach(() => {
  setUnauthorizedListener(null);
  setRefreshHandler(null);
  clearSession();
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
    setSession({ token: 'tok123', expiresAt: Date.now() + 3_600_000 });
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => ({ data: {}, status: 200, statusText: 'OK', headers: {}, config }));
    await http.get('/portfolio/summary', { adapter });
    expect(adapter.mock.calls[0][0].headers.get('Authorization')).toBe('Bearer tok123');
  });

  it('sends no Authorization header without a session', async () => {
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => ({ data: [], status: 200, statusText: 'OK', headers: {}, config }));
    await http.get('/market/quotes', { adapter });
    expect(adapter.mock.calls[0][0].headers.get('Authorization')).toBeUndefined();
  });

  it('marks every request as coming from the web client (CSRF defence for cookie endpoints)', async () => {
    const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => ({ data: {}, status: 200, statusText: 'OK', headers: {}, config }));
    await http.post('/auth/refresh', undefined, { adapter });
    expect(adapter.mock.calls[0][0].headers.get('X-TradeFlow-Client')).toBe('web');
  });

  it('on a 401 refreshes once and retries with the new token', async () => {
    setSession({ token: 'stale', expiresAt: Date.now() + 3_600_000 });
    setRefreshHandler(async () => { setSession({ token: 'fresh', expiresAt: Date.now() + 3_600_000 }); return true; });
    const seen: (string | undefined)[] = [];
    const adapter = async (config: InternalAxiosRequestConfig) => {
      const auth = config.headers.get('Authorization') as string | undefined;
      seen.push(auth);
      if (auth === 'Bearer stale') {
        // Built from the real request config, so the retry goes through this adapter too.
        throw new AxiosError('failed', undefined, config, null,
          { status: 401, data: { code: 'UNAUTHENTICATED' }, headers: {}, statusText: '', config });
      }
      return { data: { ok: true }, status: 200, statusText: 'OK', headers: {}, config };
    };
    await expect(http.get('/orders', { adapter })).resolves.toMatchObject({ data: { ok: true } });
    expect(seen).toEqual(['Bearer stale', 'Bearer fresh']);
  });

  it('ends the session when the refresh also fails, but not for a failed login', async () => {
    const listener = vi.fn();
    setUnauthorizedListener(listener);
    setRefreshHandler(async () => false);
    setSession({ token: 'tok', expiresAt: Date.now() + 3_600_000 });
    const reject401 = (url: string) => async () => {
      throw axiosError(401, { code: 'UNAUTHENTICATED' }, {}, undefined, url);
    };
    await expect(http.post('/auth/login', {}, { adapter: reject401('/auth/login') })).rejects.toMatchObject({ status: 401 });
    expect(listener).not.toHaveBeenCalled();
    await expect(http.get('/orders', { adapter: reject401('/orders') })).rejects.toMatchObject({ code: 'UNAUTHENTICATED' });
    expect(listener).toHaveBeenCalledOnce();
    expect(currentToken()).toBeNull();
  });
});

describe('environment guard', () => {
  const respond = (header?: string) => async (config: InternalAxiosRequestConfig) => ({
    data: { ok: true }, status: 200, statusText: 'OK', config,
    headers: header ? { 'x-tradeflow-environment': header } : {},
  });

  it('accepts demo data only when the gateway labels it DEMO', async () => {
    await expect(http.get('/demo/orders', { adapter: respond('DEMO') })).resolves.toMatchObject({ data: { ok: true } });
    await expect(http.get('/demo/orders', { adapter: respond('LIVE') })).rejects.toMatchObject({ code: 'ENVIRONMENT_MISMATCH' });
    await expect(http.get('/demo/orders', { adapter: respond() })).rejects.toMatchObject({ code: 'ENVIRONMENT_MISMATCH' });
  });

  it('accepts live data only when labelled LIVE', async () => {
    await expect(http.get('/live/funds', { adapter: respond('DEMO') })).rejects.toMatchObject({ code: 'ENVIRONMENT_MISMATCH' });
    await expect(http.get('/live/funds', { adapter: respond('LIVE') })).resolves.toBeTruthy();
  });

  it('does not require a label on environment-neutral paths', async () => {
    await expect(http.get('/users/me', { adapter: respond() })).resolves.toBeTruthy();
  });
});
