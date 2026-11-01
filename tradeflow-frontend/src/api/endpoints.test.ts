import type { InternalAxiosRequestConfig } from 'axios';
import { afterEach, describe, expect, it } from 'vitest';
import { http } from './client';
import { adminLiveApi, liveApi } from './endpoints';
import { clearSession, setSession } from '../auth/session';

const original = http.defaults.adapter;
afterEach(() => { http.defaults.adapter = original; clearSession(); });

function record() {
  const seen: { method?: string; url?: string; auth?: string; stepUp?: string }[] = [];
  http.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
    seen.push({ method: config.method, url: config.url, auth: config.headers.get('Authorization') as string,
      stepUp: config.headers.get('X-TradeFlow-Step-Up') as string });
    return { data: {}, status: 200, statusText: 'OK', config, headers: { 'x-tradeflow-environment': 'LIVE' } };
  };
  return seen;
}

describe('calls that need a step-up token', () => {
  it('kill-switch changes send the administrator\'s step-up token, reads use the access token', async () => {
    setSession({ token: 'access', expiresAt: Date.now() + 3_600_000 });
    const seen = record();
    await adminLiveApi.setKillSwitch('admin-stepup', true, 'Broker outage drill');
    await adminLiveApi.setUserKillSwitch('admin-stepup', 5, false, 'Review complete');
    await adminLiveApi.killSwitch();
    expect(seen).toEqual([
      { method: 'put', url: '/admin/live/kill-switch', auth: 'Bearer admin-stepup', stepUp: '1' },
      { method: 'put', url: '/admin/live/users/5/kill-switch', auth: 'Bearer admin-stepup', stepUp: '1' },
      { method: 'get', url: '/admin/live/kill-switch', auth: 'Bearer access', stepUp: undefined },
    ]);
  });

  it('resolving an order under review needs step-up; checking with the broker does not', async () => {
    setSession({ token: 'access', expiresAt: Date.now() + 3_600_000 });
    const seen = record();
    await liveApi.resolve('stepup', 'L-1');
    await liveApi.reconcile();
    expect(seen).toEqual([
      { method: 'post', url: '/live/orders/L-1/resolve', auth: 'Bearer stepup', stepUp: '1' },
      { method: 'post', url: '/live/reconciliation', auth: 'Bearer access', stepUp: undefined },
    ]);
  });
});

describe('the linked broker account', () => {
  it('is read with the ordinary access token, GET only', async () => {
    setSession({ token: 'access', expiresAt: Date.now() + 3_600_000 });
    const seen = record();
    await liveApi.brokerFunds();
    await liveApi.brokerHoldings();
    await liveApi.brokerPositions();
    await liveApi.brokerOrders();
    expect(seen).toEqual(['funds', 'holdings', 'positions', 'orders'].map((what) => (
      { method: 'get', url: `/live/broker/${what}`, auth: 'Bearer access', stepUp: undefined })));
  });
});
