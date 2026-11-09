import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { Ban, CheckCircle2, Circle, Link2, Link2Off, PlugZap, Radio } from 'lucide-react';
import { ApiError } from '../api/client';
import { liveApi, mfaApi } from '../api/endpoints';
import type { LiveOrderParams, LiveOrderPreview, LiveOrderType, LiveOrderView, LiveStatus } from '../api/types';
import { BrokerAccountCard } from './BrokerAccount';
import { StepUpCancelled, useStepUp } from '../components/StepUp';
import { Alert, Badge, Button, Card, ConfirmDialog, EmptyState, ErrorState, PageHeader, Segmented, SelectInput, Spinner, TextInput, useToast } from '../components/ui';
import { dateTime, money } from '../lib/format';
import { invalidate, useApi } from '../lib/useApi';

const message = (e: unknown) => (e instanceof ApiError ? e.message : String(e));

/** Plain-language text for the readiness service's reason codes (e.g. NOT_QUALIFIED:IN_PROGRESS). */
function readinessReason(code: string): string {
  if (code === 'QUALIFICATION_EXPIRED') return 'Your qualification has expired.';
  if (code === 'SELF_EXCLUDED') return 'You have paused live trading for yourself.';
  if (code.startsWith('NOT_QUALIFIED')) {
    const state = code.split(':')[1]?.toLowerCase().replace(/_/g, ' ');
    return state ? `Not qualified yet (${state}).` : 'Not qualified yet.';
  }
  return code;
}

function Check({ ok, title, children }: { ok: boolean; title: string; children?: ReactNode }) {
  return (
    <li className="flex gap-2.5 p-4" data-testid={`live-check-${ok ? 'ok' : 'missing'}`}>
      {ok ? <CheckCircle2 className="mt-0.5 size-4.5 shrink-0 text-emerald-600" aria-label="Done" />
        : <Circle className="mt-0.5 size-4.5 shrink-0 text-slate-300" aria-label="Not done" />}
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-slate-900">{title}</p>
        {children && <div className="mt-0.5 text-xs leading-5 text-slate-500">{children}</div>}
      </div>
    </li>
  );
}

/** Everything about live trading on one page. Every check shown here is enforced again by live-trading-service. */
export function LiveDashboardPage() {
  const status = useApi(() => liveApi.status(), [], { topics: ['live', 'readiness'] });
  const mfa = useApi(() => mfaApi.status(), []);
  if (status.error) return <Card><ErrorState error={status.error} onRetry={status.reload} /></Card>;
  if (!status.data) return <Spinner />;
  const s = status.data;
  const canTrade = s.brokerConfigured && s.ordersEnabled && s.eligibility.eligible && !!s.connection && !s.halted && !!mfa.data?.enabled;
  return (
    <>
      <PageHeader icon={<Radio className="size-5" />} tone="rose" title="Live trading" subtitle="Your real broker account. TradeFlow does not give investment advice." />
      {!s.brokerConfigured && (
        <div className="mb-6" data-testid="no-broker">
          <Alert tone="warning" title="No broker configured">
            {s.notice ?? 'No broker is integrated yet.'} Nothing on this page can place a real order until then. You can
            keep practising in <Link className="font-medium underline" to="/demo">demo trading</Link>.
          </Alert>
        </div>
      )}
      {s.brokerConfigured && !s.ordersEnabled && (
        <div className="mb-6" data-testid="read-only-broker">
          <Alert tone="info" title="Read-only broker link">
            {s.notice} Manage orders directly with your broker until order placement is enabled.
          </Alert>
        </div>
      )}
      {s.halted && <div className="mb-6"><Alert tone="error" title="New live orders are halted">{s.haltReason} You can still view and cancel existing orders.</Alert></div>}
      <section className="mb-6 rounded-xl border border-slate-200 bg-surface shadow-soft" aria-label="Before you can trade live">
        <ul className="grid divide-y divide-slate-100 sm:grid-cols-2 sm:divide-y-0 lg:grid-cols-5 lg:divide-x">
          <Check ok={s.eligibility.eligible} title="Trading readiness">
            {!s.eligibility.available ? 'Could not be checked right now; live actions are refused until it can.'
              : s.eligibility.eligible ? <>Qualified{s.eligibility.expiresAt && <> until {dateTime(s.eligibility.expiresAt)}</>}.</>
              : <>{s.eligibility.reasons.map(readinessReason).join(' ') || 'Not qualified yet.'} <Link className="font-medium text-indigo-600 hover:underline" to="/readiness">Go to readiness</Link></>}
          </Check>
          <Check ok={!!mfa.data?.enabled} title="Two-factor authentication">
            {mfa.data?.enabled ? 'Enabled.' : <><Link className="font-medium text-indigo-600 hover:underline" to="/settings/security">Set up an authenticator app</Link>; every live action asks for a code.</>}
          </Check>
          <Check ok={s.brokerConfigured} title="Broker integration">
            {s.brokerConfigured ? `${s.broker}${s.ordersEnabled ? '' : ' (read-only)'}` : 'Not available yet.'}
          </Check>
          <Check ok={!!s.connection} title="Broker account linked">
            {s.connection ? `${s.connection.accountRef ?? 'Account'} · ${dateTime(s.connection.connectedAt)}` : 'Not linked.'}
          </Check>
          <Check ok={!s.halted} title="Live trading open">{s.halted ? s.haltReason : 'No halt in force.'}</Check>
        </ul>
      </section>
      <div className="grid gap-6 lg:grid-cols-3">
        <div className="min-w-0 space-y-6 lg:col-span-2">
          {s.connection ? <BrokerAccountCard /> : (
            <Card title="Your broker account">
              <EmptyState title="No broker account linked">Link your broker account to see funds, holdings, positions and orders here.</EmptyState>
            </Card>
          )}
          {canTrade && <LiveOrderTicket />}
          <LiveOrdersCard />
        </div>
        <div className="min-w-0 space-y-6">
          <BrokerConnectionCard status={s} />
          <LimitsCard />
          <NotificationsCard />
        </div>
      </div>
      <p className="mt-6 text-xs text-slate-500">Passing the Academy is not financial advice, a suitability assessment or a guarantee of trading competence. Your broker applies its own checks.</p>
    </>
  );
}

function BrokerConnectionCard({ status: s }: { status: LiveStatus }) {
  const stepUp = useStepUp();
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function act(kind: 'connect' | 'disconnect') {
    setBusy(true); setError(null);
    try {
      if (kind === 'connect') {
        const res = await stepUp((t) => liveApi.connect(t));
        window.location.assign(res.authorizationUrl); // the broker's own login and consent page
        return;
      }
      await stepUp((t) => liveApi.disconnect(t));
      toast('success', 'Broker disconnected. Orders at the broker were not cancelled.');
      invalidate('live');
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Card title="Broker account">
      {s.connection ? (
        <div className="space-y-3 text-sm">
          <p className="flex items-center gap-2"><Link2 className="size-4 text-emerald-600" /> Linked to <strong>{s.connection.broker}</strong></p>
          <p className="text-slate-600">Disconnecting erases TradeFlow's access tokens. It does not cancel orders already at your broker.</p>
          <Button variant="secondary" onClick={() => act('disconnect')} loading={busy}><Link2Off className="size-4" /> Disconnect</Button>
        </div>
      ) : (
        <div className="space-y-3 text-sm">
          <p className="text-slate-600">You sign in on your broker's own page. TradeFlow never sees your broker password or one-time codes.</p>
          <Button onClick={() => act('connect')} loading={busy} disabled={!s.brokerConfigured || !s.eligibility.eligible || s.halted}
            data-testid="connect-broker"><PlugZap className="size-4" /> Connect broker</Button>
        </div>
      )}
      {error && <div className="mt-3"><Alert tone="error">{error}</Alert></div>}
    </Card>
  );
}

function LiveOrderTicket() {
  const stepUp = useStepUp();
  const toast = useToast();
  const [exchange, setExchange] = useState<'NSE' | 'BSE'>('NSE');
  const [symbol, setSymbol] = useState('');
  const [side, setSide] = useState<'BUY' | 'SELL'>('BUY');
  const [type, setType] = useState<LiveOrderType>('LIMIT');
  const [qty, setQty] = useState('1');
  const [limit, setLimit] = useState('');
  const [trigger, setTrigger] = useState('');
  const [preview, setPreview] = useState<LiveOrderPreview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const clientOrderId = useRef<string>('');

  const needsLimit = type === 'LIMIT' || type === 'STOP_LIMIT';
  const needsTrigger = type === 'STOP' || type === 'STOP_LIMIT';
  const order = (): LiveOrderParams => ({
    exchange, symbol: symbol.trim().toUpperCase(), side, type, quantity: Number(qty),
    limitPrice: needsLimit ? Number(limit) : null, triggerPrice: needsTrigger ? Number(trigger) : null,
  });

  async function review(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      setPreview(await stepUp((t) => liveApi.preview(t, order())));
      clientOrderId.current = crypto.randomUUID(); // one id per confirmed order: retries cannot double-send
    } catch (err) {
      if (!(err instanceof StepUpCancelled)) setError(message(err));
    } finally {
      setBusy(false);
    }
  }

  async function place() {
    if (!preview) return;
    setBusy(true); setError(null);
    try {
      const placed = await stepUp((t) => liveApi.place(t, preview.confirmationId, clientOrderId.current, preview.order));
      toast(placed.status === 'UNKNOWN' || placed.status === 'REJECTED' ? 'error' : 'success',
        `Order ${placed.status}${placed.statusMessage ? `: ${placed.statusMessage}` : ''}`);
      setPreview(null);
      invalidate('live');
    } catch (err) {
      if (!(err instanceof StepUpCancelled)) setError(message(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Place a live order" className="mt-6">
      <form onSubmit={review} className="grid gap-3 sm:grid-cols-4" data-testid="live-order-ticket">
        <SelectInput label="Exchange" value={exchange} onChange={(e) => setExchange(e.target.value as 'NSE' | 'BSE')}>
          <option>NSE</option><option>BSE</option>
        </SelectInput>
        <TextInput label="Symbol" value={symbol} onChange={(e) => setSymbol(e.target.value)} required />
        <Segmented label="Side" value={side} onChange={setSide} options={[{ value: 'BUY', label: 'Buy' }, { value: 'SELL', label: 'Sell' }]} />
        <SelectInput label="Type" value={type} onChange={(e) => setType(e.target.value as LiveOrderType)}>
          <option value="LIMIT">Limit</option><option value="MARKET">Market</option>
          <option value="STOP">Stop</option><option value="STOP_LIMIT">Stop-limit</option>
        </SelectInput>
        <TextInput label="Quantity" inputMode="numeric" value={qty} onChange={(e) => setQty(e.target.value.replace(/\D/g, ''))} required />
        {needsLimit && <TextInput label="Limit price" inputMode="decimal" value={limit} onChange={(e) => setLimit(e.target.value)} required />}
        {needsTrigger && <TextInput label="Trigger price" inputMode="decimal" value={trigger} onChange={(e) => setTrigger(e.target.value)} required />}
        <div className="flex items-end"><Button type="submit" variant="danger" loading={busy && !preview}>Review order</Button></div>
      </form>
      {error && !preview && <div className="mt-3"><Alert tone="error">{error}</Alert></div>}
      <ConfirmDialog open={!!preview} title="Confirm REAL order" confirmLabel="Place real order" variant="danger" busy={busy}
        error={error} onConfirm={place} onCancel={() => { setPreview(null); setError(null); }}>
        {preview && (
          <div className="space-y-2">
            <p className="text-base font-semibold">{preview.order.side} {preview.order.quantity} {preview.order.exchange}:{preview.order.symbol} · {preview.order.type}</p>
            <p>Estimated value: <strong>{money(preview.estimatedValue)}</strong></p>
            <p className="text-xs text-slate-500">Today: {preview.ordersToday} of {preview.limits.maxOrdersPerDay} orders, {money(preview.turnoverToday)} of {money(preview.limits.maxDailyTurnover)} turnover.
              This confirmation expires {dateTime(preview.expiresAt)}.</p>
            <Alert tone="warning">{preview.warning}</Alert>
          </div>
        )}
      </ConfirmDialog>
    </Card>
  );
}

function LimitsCard() {
  const limits = useApi(() => liveApi.limits(), [], { topics: ['live'] });
  const stepUp = useStepUp();
  const toast = useToast();
  const [form, setForm] = useState<{ v: string; n: string; t: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => {
    if (limits.data && !form) {
      const e = limits.data.effective;
      setForm({ v: String(e.maxOrderValue), n: String(e.maxOrdersPerDay), t: String(e.maxDailyTurnover) });
    }
  }, [limits.data, form]);
  if (limits.error) return <Card title="Your limits"><ErrorState error={limits.error} onRetry={limits.reload} compact /></Card>;
  if (!limits.data || !form) return <Card title="Your limits"><Spinner /></Card>;
  const p = limits.data.platform;
  async function save(e: FormEvent) {
    e.preventDefault();
    if (!form) return;
    const next = { maxOrderValue: Number(form.v), maxOrdersPerDay: Number(form.n), maxDailyTurnover: Number(form.t) };
    setBusy(true); setError(null);
    try {
      // The server decides whether this raises a limit; if so it answers STEP_UP_REQUIRED and we confirm.
      await liveApi.setLimits(next).catch((err) => {
        if (err instanceof ApiError && err.code === 'STEP_UP_REQUIRED') return stepUp((t) => liveApi.setLimits(next, t));
        throw err;
      });
      toast('success', 'Limits saved');
      invalidate('live');
    } catch (err) {
      if (!(err instanceof StepUpCancelled)) setError(message(err));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Card title="Your limits">
      <form onSubmit={save} className="space-y-3">
        <p className="text-xs text-slate-500">You can only set limits at or below the platform maximum. Raising a limit asks you to confirm.</p>
        <TextInput label={`Max order value (platform max ${money(p.maxOrderValue)})`} inputMode="decimal" value={form.v} onChange={(e) => setForm({ ...form, v: e.target.value })} />
        <TextInput label={`Max orders per day (platform max ${p.maxOrdersPerDay})`} inputMode="numeric" value={form.n} onChange={(e) => setForm({ ...form, n: e.target.value.replace(/\D/g, '') })} />
        <TextInput label={`Max daily turnover (platform max ${money(p.maxDailyTurnover)})`} inputMode="decimal" value={form.t} onChange={(e) => setForm({ ...form, t: e.target.value })} />
        {error && <Alert tone="error">{error}</Alert>}
        <Button type="submit" variant="secondary" loading={busy}>Save limits</Button>
      </form>
    </Card>
  );
}

function NotificationsCard() {
  const notes = useApi(() => liveApi.notifications(), [], { topics: ['live'] });
  const toast = useToast();
  return (
    <Card title="Notifications">
      {notes.error ? <ErrorState error={notes.error} onRetry={notes.reload} compact />
        : !notes.data ? <Spinner />
        : notes.data.length === 0 ? <EmptyState title="No notifications" />
        : (
          <ul className="max-h-72 space-y-2 overflow-y-auto text-sm" data-testid="live-notifications">
            {notes.data.map((n) => (
              <li key={n.id} className={n.readAt ? 'text-slate-500' : 'text-slate-900'}>
                <p className="flex items-center justify-between gap-2">
                  <Badge className="bg-slate-100 text-slate-700">{n.kind}</Badge>
                  <span className="text-xs text-slate-400">{dateTime(n.createdAt)}</span>
                </p>
                <p className="mt-1">{n.message}</p>
                {!n.readAt && <button className="mt-1 text-xs font-medium text-slate-500 underline"
                  onClick={() => liveApi.markRead(n.id).then(() => invalidate('live'),
                    (e) => toast('error', e instanceof ApiError ? e.message : String(e)))}>Mark as read</button>}
              </li>
            ))}
          </ul>
        )}
    </Card>
  );
}

function LiveOrdersCard() {
  const orders = useApi(() => liveApi.orders(), [], { topics: ['live'] });
  const stepUp = useStepUp();
  const toast = useToast();
  const [error, setError] = useState<string | null>(null);
  const [checking, setChecking] = useState(false);
  const [resolving, setResolving] = useState<LiveOrderView | null>(null);
  async function cancel(id: string) {
    setError(null);
    try {
      await stepUp((t) => liveApi.cancel(t, id));
      toast('success', 'Cancellation requested at the broker');
      invalidate('live');
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) setError(message(e));
    }
  }
  async function checkNow() {
    setError(null); setChecking(true);
    try {
      await liveApi.reconcile();
      invalidate('live');
    } catch (e) { setError(message(e)); } finally { setChecking(false); }
  }
  async function resolve(o: LiveOrderView) {
    setError(null);
    try {
      await stepUp((t) => liveApi.resolve(t, o.id));
      toast('success', 'Marked as resolved. New live orders are possible again.');
      invalidate('live');
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) setError(message(e));
    }
  }
  const unresolved = orders.data?.filter((o) => UNRESOLVED.includes(o.status)) ?? [];
  return (
    <Card title={<span className="flex items-center gap-2"><Radio className="size-4 text-rose-600" /> Live orders</span>} className="mt-6">
      {error && <div className="mb-3"><Alert tone="error">{error}</Alert></div>}
      {unresolved.length > 0 && (
        <div className="mb-3" data-testid="live-unresolved">
          <Alert tone="warning" title="An order's outcome at your broker is not confirmed">
            TradeFlow never resubmits such an order. It checks your broker's order book every minute; until the order
            is found or you have checked it yourself, new live orders are blocked so nothing is placed twice.
            <div className="mt-2"><Button size="sm" variant="secondary" onClick={checkNow} loading={checking}>Check with broker now</Button></div>
          </Alert>
        </div>
      )}
      {orders.error ? <ErrorState error={orders.error} onRetry={orders.reload} compact />
        : !orders.data ? <Spinner />
        : orders.data.length === 0 ? <EmptyState title="No live orders">Orders you place with your broker through TradeFlow appear here.</EmptyState>
        : (
          <table className="w-full text-sm">
            <thead><tr className="text-left text-xs text-slate-500"><th className="py-1">Order</th><th>Value</th><th>Status</th><th>Placed</th><th /></tr></thead>
            <tbody>
              {orders.data.map((o) => (
                <tr key={o.id} className="border-t border-slate-100">
                  <td className="py-2">{o.order.side} {o.order.quantity} {o.order.exchange}:{o.order.symbol} <span className="text-xs text-slate-500">{o.order.type}</span></td>
                  <td className="tabular-nums">{money(o.orderValue)}</td>
                  <td>{o.status}{o.filledQuantity > 0 && <span className="text-xs text-slate-500"> ({o.filledQuantity}/{o.order.quantity} filled)</span>}
                    {o.statusMessage && <p className="text-xs text-slate-500">{o.statusMessage}</p>}</td>
                  <td className="text-xs text-slate-500">{dateTime(o.createdAt)}</td>
                  <td className="text-right">{o.brokerOrderId && ['OPEN', 'PARTIALLY_FILLED'].includes(o.status) && (
                    <Button size="sm" variant="secondary" onClick={() => cancel(o.id)}><Ban className="size-3.5" /> Cancel</Button>)}
                    {o.status === 'NEEDS_REVIEW' && (
                      <Button size="sm" variant="secondary" onClick={() => setResolving(o)}>I've checked my broker</Button>)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      <ConfirmDialog
        open={!!resolving}
        title="Mark this order as resolved?"
        confirmLabel="Yes, I checked my broker account"
        onCancel={() => setResolving(null)}
        onConfirm={() => { const o = resolving; setResolving(null); if (o) void resolve(o); }}
      >
        Only confirm after you have looked at this order in your broker account and dealt with it there (cancelled a
        duplicate, or confirmed it is not there). TradeFlow will not send anything to the broker. New live orders become
        possible again.
      </ConfirmDialog>
    </Card>
  );
}

const UNRESOLVED = ['SUBMITTING', 'UNKNOWN', 'NEEDS_REVIEW'];

/**
 * The broker redirects here after the user signs in on its page; finishing the link needs a fresh
 * step-up. Angel One appends `auth_token` (its login token) and the `state` TradeFlow issued. The
 * values are read once and the query string is removed at once, so the token does not stay in the
 * address bar or browser history.
 */
export function BrokerCallbackPage() {
  const [params] = useSearchParams();
  const stepUp = useStepUp();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [{ state, code, declined, declineMessage }] = useState(() => ({
    state: params.get('state') ?? '',
    code: params.get('auth_token') ?? params.get('code') ?? '',
    declined: params.get('status') === 'false' || params.get('s') === 'error',
    declineMessage: params.get('message'),
  }));
  useEffect(() => {
    if (window.location.search) navigate('/live/broker/callback', { replace: true });
  }, [navigate]);
  async function finish() {
    setBusy(true); setError(null);
    try {
      await stepUp((t) => liveApi.completeConnection(t, state, code));
      invalidate('live');
      navigate('/live', { replace: true });
    } catch (e) {
      if (!(e instanceof StepUpCancelled)) setError(message(e));
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <PageHeader title="Finish linking your broker" />
      <Card>
        {declined ? <Alert tone="error">The broker did not authorise the link{declineMessage ? `: ${declineMessage}` : ''}. Start again from Live trading.</Alert>
          : !code ? <Alert tone="error">This page was opened without a broker authorisation. Start again from Live trading.</Alert>
          : !state ? <Alert tone="error" title="Linking stopped for your safety">Your broker signed you in but did not return TradeFlow's security check (the state value), so the link cannot be verified as yours. Nothing was linked. Start again from Live trading; if this keeps happening, the broker's redirect is not passing the state back.</Alert> : (
          <div className="space-y-3 text-sm">
            <p>Your broker sent you back to TradeFlow. Confirm to finish linking the account.</p>
            {error && <Alert tone="error">{error}</Alert>}
            <Button onClick={finish} loading={busy}>Confirm and link</Button>
          </div>
        )}
      </Card>
    </>
  );
}
