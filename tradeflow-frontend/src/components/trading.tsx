import { ArrowDownRight, ArrowUpRight, Ban, CircleCheck, CircleDashed, Clock, Hourglass, LoaderCircle, XCircle } from 'lucide-react';
import { Link } from 'react-router';
import type { AlertStatus, OrderSide, OrderStatus } from '../api/types';
import { signedMoney, signedPercent, trendClass } from '../lib/format';
import { Badge, cx } from './ui';

const statusStyles: Record<OrderStatus, { cls: string; icon: typeof Clock; hint: string }> = {
  PENDING: { cls: 'bg-amber-50 text-amber-700 ring-1 ring-amber-200', icon: LoaderCircle, hint: 'Funds/position reservation in progress' },
  OPEN: { cls: 'bg-indigo-50 text-indigo-700 ring-1 ring-indigo-200', icon: Clock, hint: 'Working; fills when the simulated market allows it' },
  PARTIALLY_FILLED: { cls: 'bg-sky-50 text-sky-700 ring-1 ring-sky-200', icon: CircleDashed, hint: 'Part executed; the rest keeps working on later ticks' },
  FILLED: { cls: 'bg-emerald-50 text-emerald-700 ring-1 ring-emerald-200', icon: CircleCheck, hint: 'Executed in full' },
  REJECTED: { cls: 'bg-rose-50 text-rose-700 ring-1 ring-rose-200', icon: XCircle, hint: 'Rejected by risk checks' },
  CANCELLED: { cls: 'bg-slate-100 text-slate-600 ring-1 ring-slate-200', icon: Ban, hint: 'Cancelled; any unfilled part was released' },
  EXPIRED: { cls: 'bg-slate-100 text-slate-600 ring-1 ring-slate-200', icon: Hourglass, hint: 'Time limit reached; any unfilled part was released' },
};

export function OrderStatusBadge({ status }: { status: OrderStatus }) {
  const s = statusStyles[status];
  const Icon = s.icon;
  return (
    <Badge className={s.cls}>
      <Icon className={cx('size-3', status === 'PENDING' && 'animate-spin')} aria-hidden />
      <span title={s.hint}>{status.replace('_', ' ')}</span>
    </Badge>
  );
}

export function AlertStatusBadge({ status }: { status: AlertStatus }) {
  const cls = status === 'ACTIVE' ? 'bg-indigo-50 text-indigo-700 ring-1 ring-indigo-200'
    : status === 'TRIGGERED' ? 'bg-amber-50 text-amber-800 ring-1 ring-amber-200'
      : 'bg-slate-100 text-slate-600 ring-1 ring-slate-200';
  return <Badge className={cls}>{status}</Badge>;
}

export function SideBadge({ side }: { side: OrderSide }) {
  return (
    <span className={cx('inline-flex rounded-md px-1.5 py-0.5 text-[11px] font-bold tracking-wide',
      side === 'BUY' ? 'bg-emerald-50 text-emerald-700' : 'bg-rose-50 text-rose-700')}>{side}</span>
  );
}

/** Coloured, arrowed change: "+12.30 (+1.20%)". */
export function Change({ change, ratio, money = true, className }: {
  change?: number | null; ratio?: number | null; money?: boolean; className?: string;
}) {
  const v = change ?? ratio ?? null;
  const Icon = v == null || v >= 0 ? ArrowUpRight : ArrowDownRight;
  return (
    <span className={cx('inline-flex items-center gap-0.5 tabular-nums', trendClass(v), className)}>
      {v != null && v !== 0 && <Icon className="size-3.5" aria-hidden />}
      {change != null && (money ? signedMoney(change) : change)}
      {change != null && ratio != null && ' '}
      {ratio != null && (change != null ? `(${signedPercent(ratio)})` : signedPercent(ratio))}
    </span>
  );
}

const AVATAR_TONES = [
  'bg-indigo-50 text-indigo-700', 'bg-slate-100 text-slate-700', 'bg-amber-50 text-amber-800',
  'bg-teal-50 text-teal-700', 'bg-sky-50 text-sky-700', 'bg-rose-50 text-rose-700', 'bg-violet-50 text-violet-700',
];

/** Decorative monogram for an instrument; the colour is stable per symbol. */
export function SymbolAvatar({ symbol, className }: { symbol: string; className?: string }) {
  let h = 0;
  for (const c of symbol) h = (h * 31 + c.charCodeAt(0)) >>> 0;
  return (
    <span aria-hidden className={cx('flex size-9 shrink-0 items-center justify-center rounded-lg text-[11px] font-semibold tracking-tight',
      AVATAR_TONES[h % AVATAR_TONES.length], className)}>
      {symbol.slice(0, 2)}
    </span>
  );
}

export function SymbolLink({ symbol, name }: { symbol: string; name?: string }) {
  return (
    <Link to={`/demo/market/${encodeURIComponent(symbol)}`} className="group flex min-w-0 items-center gap-3">
      <SymbolAvatar symbol={symbol} />
      <span className="min-w-0">
        <span className="block font-semibold text-slate-900 transition-colors group-hover:text-indigo-600">{symbol}</span>
        {name && <span className="block truncate text-xs text-slate-500">{name}</span>}
      </span>
    </Link>
  );
}

export function SimulatedNote({ className }: { className?: string }) {
  return (
    <p className={cx('text-xs text-slate-400', className)}>
      Prices are simulated demo data from TradeFlow's market simulator, not live market data.
    </p>
  );
}
