import { Outlet } from 'react-router';
import { FlaskConical, Radio } from 'lucide-react';
import { cx } from './ui';
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

/** Layout for every simulated-trading page: a persistent DEMO banner above the content. */
export function DemoArea() {
  return (
    <>
      <div className="mb-6 flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border border-sky-200 bg-sky-50 px-4 py-2.5 text-sm text-sky-900"
        role="note" data-testid="demo-banner">
        <EnvironmentBadge env="DEMO" />
        <span><strong>Demo trading.</strong> Simulated prices and virtual money. Nothing here reaches a real broker or market.</span>
      </div>
      <Outlet />
    </>
  );
}

/** Layout for every real-money page: a persistent red LIVE banner, and step-up confirmation. */
export function LiveArea() {
  return (
    <StepUpProvider>
      <div className="mb-6 flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border-2 border-rose-500 bg-rose-50 px-4 py-2.5 text-sm text-rose-900"
        role="note" data-testid="live-banner">
        <EnvironmentBadge env="LIVE" />
        <span><strong>Live trading.</strong> Real broker account, real money. Orders here can lose money and are not simulated.</span>
      </div>
      <Outlet />
    </StepUpProvider>
  );
}
