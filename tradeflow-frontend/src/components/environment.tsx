import { useState } from 'react';
import { Link, Outlet, useNavigate } from 'react-router';
import { useAuth } from '../auth/AuthContext';
import { LIVE_ACK_PREFIX } from '../auth/session';
import { FlaskConical, Radio } from 'lucide-react';
import { marketApi } from '../api/endpoints';
import type { QuoteResponse } from '../api/types';
import { money, signedPercent } from '../lib/format';
import { useApi } from '../lib/useApi';
import { Button, cx, Flash, Modal } from './ui';
import { StepUpProvider } from './StepUp';

export type Env = 'DEMO' | 'LIVE';

/** Every surface that shows trading data carries one of these, so DEMO and LIVE are never confused. */
export function EnvironmentBadge({ env, size = 'sm' }: { env: Env; size?: 'xs' | 'sm' }) {
  const live = env === 'LIVE';
  return (
    <span
      data-testid={`env-badge-${env}`}
      className={cx('inline-flex items-center gap-1 rounded font-semibold tracking-wide ring-1 ring-inset',
        size === 'xs' ? 'px-1.5 py-px text-[9px]' : 'px-1.5 py-0.5 text-[11px]',
        live ? 'bg-red-50 text-red-700 ring-red-200' : 'bg-slate-100 text-slate-600 ring-slate-200')}
    >
      {live ? <Radio className={size === 'xs' ? 'size-2.5' : 'size-3'} /> : <FlaskConical className={size === 'xs' ? 'size-2.5' : 'size-3'} />}
      {env}
    </span>
  );
}

function TickerItem({ q }: { q: QuoteResponse }) {
  const up = (q.changeRatio ?? 0) >= 0;
  return (
    <Link to={`/demo/market/${encodeURIComponent(q.symbol)}`}
      className="flex shrink-0 items-center gap-2 rounded-md px-3 py-1 text-xs transition-colors hover:bg-slate-100">
      <span className="font-semibold text-slate-800">{q.symbol}</span>
      <Flash value={q.price} className="tabular-nums text-slate-600">{money(q.price)}</Flash>
      {q.changeRatio != null && (
        <span className={cx('tabular-nums font-medium', up ? 'text-emerald-600' : 'text-rose-600')}>
          {up ? '▲' : '▼'} {signedPercent(q.changeRatio)}
        </span>
      )}
    </Link>
  );
}

/** Scrolling strip of the simulator's current quotes (pauses on hover). */
function MarketTicker() {
  const quotes = useApi(() => marketApi.quotes(), [], { topics: ['market'], intervalMs: 15_000 });
  if (!quotes.data?.length) return null;
  const items = quotes.data;
  return (
    <div className="group relative overflow-hidden border-t border-slate-100 py-1 [mask-image:linear-gradient(90deg,transparent,black_6%,black_94%,transparent)]">
      <div className="flex w-max animate-marquee group-hover:[animation-play-state:paused]"
        style={{ ['--marquee-duration' as string]: `${Math.max(30, items.length * 4)}s` }}>
        <div className="flex" aria-label="Simulated quotes">{items.map((q) => <TickerItem key={q.symbol} q={q} />)}</div>
        <div className="flex" aria-hidden inert>{items.map((q) => <TickerItem key={q.symbol} q={q} />)}</div>
      </div>
    </div>
  );
}

/** Layout for every simulated-trading page: a quiet DEMO strip with the simulator's ticker. */
export function DemoArea() {
  return (
    <>
      <div className="mb-6 overflow-hidden rounded-lg border border-slate-200 bg-surface text-sm text-slate-600">
        <div className="flex flex-wrap items-center gap-x-2.5 gap-y-1 px-4 py-2" role="note" data-testid="demo-banner">
          <EnvironmentBadge env="DEMO" />
          <span>Simulated prices and virtual money. Nothing here reaches a real broker or market.</span>
        </div>
        <MarketTicker />
      </div>
      <Outlet />
    </>
  );
}

/** The real-money warning: shown once per sign-in before any Live page is displayed. */
function LiveWarning({ onContinue }: { onContinue: () => void }) {
  const navigate = useNavigate();
  return (
    <Modal
      open title="You're entering live trading" onClose={() => navigate('/demo')}
      footer={<>
        <Button variant="secondary" onClick={() => navigate('/demo')}>Back to demo</Button>
        <Button onClick={onContinue} data-testid="live-continue">Continue</Button>
      </>}
    >
      <div className="space-y-3" data-testid="live-warning">
        <p>This area uses your <strong>real broker account and real money</strong>. Nothing here is simulated.</p>
        <ul className="list-disc space-y-1 pl-5 text-slate-600">
          <li>Real orders can lose money, including more than you expect in fast markets.</li>
          <li>TradeFlow does not give investment advice or judge whether a trade suits you.</li>
          <li>Sensitive actions ask for your password and authenticator code each time.</li>
        </ul>
      </div>
    </Modal>
  );
}

/** Layout for every real-money page: a one-time warning, a persistent LIVE marker, and step-up confirmation. */
export function LiveArea() {
  const { user } = useAuth();
  const key = `${LIVE_ACK_PREFIX}${user?.id ?? 'anon'}`;
  const [acknowledged, setAcknowledged] = useState(() => {
    try { return sessionStorage.getItem(key) === '1'; } catch { return false; }
  });
  if (!acknowledged) {
    return <LiveWarning onContinue={() => {
      try { sessionStorage.setItem(key, '1'); } catch { /* ask again next time */ }
      setAcknowledged(true);
    }} />;
  }
  return (
    <StepUpProvider>
      <div className="mb-5 flex flex-wrap items-center gap-x-2.5 gap-y-1 text-sm text-slate-600" role="note" data-testid="live-banner">
        <EnvironmentBadge env="LIVE" />
        <span>Real broker account, real money. Orders here are not simulated.</span>
      </div>
      <Outlet />
    </StepUpProvider>
  );
}
