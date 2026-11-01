import { Link, Outlet } from 'react-router';
import { FlaskConical, Radio } from 'lucide-react';
import { marketApi } from '../api/endpoints';
import type { QuoteResponse } from '../api/types';
import { money, signedPercent } from '../lib/format';
import { useApi } from '../lib/useApi';
import { cx, Flash } from './ui';
import { StepUpProvider } from './StepUp';

export type Env = 'DEMO' | 'LIVE';

/** Every surface that shows trading data carries one of these, so DEMO and LIVE are never confused. */
export function EnvironmentBadge({ env, size = 'sm' }: { env: Env; size?: 'xs' | 'sm' }) {
  const live = env === 'LIVE';
  return (
    <span
      data-testid={`env-badge-${env}`}
      className={cx('inline-flex items-center gap-1 rounded font-bold tracking-wider',
        size === 'xs' ? 'px-1.5 py-px text-[9px]' : 'px-2 py-0.5 text-[11px]',
        live ? 'bg-rose-600 text-white' : 'bg-sky-500/15 text-sky-600 ring-1 ring-sky-500/40')}
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
      className="flex shrink-0 items-center gap-2 rounded-md px-3 py-1 text-xs transition-colors hover:bg-sky-100/70">
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
    <div className="group relative overflow-hidden border-t border-sky-200/70 py-1 [mask-image:linear-gradient(90deg,transparent,black_6%,black_94%,transparent)]">
      <div className="flex w-max animate-marquee group-hover:[animation-play-state:paused]"
        style={{ ['--marquee-duration' as string]: `${Math.max(30, items.length * 4)}s` }}>
        <div className="flex" aria-label="Simulated quotes">{items.map((q) => <TickerItem key={q.symbol} q={q} />)}</div>
        <div className="flex" aria-hidden inert>{items.map((q) => <TickerItem key={q.symbol} q={q} />)}</div>
      </div>
    </div>
  );
}

/** Layout for every simulated-trading page: a persistent DEMO banner above the content. */
export function DemoArea() {
  return (
    <>
      <div className="mb-6 animate-enter overflow-hidden rounded-xl border border-sky-200/80 bg-gradient-to-r from-sky-50 via-white/80 to-sky-50 text-sm text-sky-900 shadow-soft">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2.5" role="note" data-testid="demo-banner">
          <EnvironmentBadge env="DEMO" />
          <span><strong>Demo trading.</strong> Simulated prices and virtual money. Nothing here reaches a real broker or market.</span>
        </div>
        <MarketTicker />
      </div>
      <Outlet />
    </>
  );
}

/** Layout for every real-money page: a persistent red LIVE banner, and step-up confirmation. */
export function LiveArea() {
  return (
    <StepUpProvider>
      <div className="mb-6 flex animate-enter flex-wrap items-center gap-x-3 gap-y-1 rounded-xl border-2 border-rose-500 bg-gradient-to-r from-rose-50 via-white to-rose-50 px-4 py-2.5 text-sm text-rose-900 shadow-[0_0_0_4px_rgba(244,63,94,0.08)]"
        role="note" data-testid="live-banner">
        <EnvironmentBadge env="LIVE" />
        <span><strong>Live trading.</strong> Real broker account, real money. Orders here can lose money and are not simulated.</span>
      </div>
      <Outlet />
    </StepUpProvider>
  );
}
