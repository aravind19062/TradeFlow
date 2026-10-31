import { ArrowDownRight, ArrowUpRight, Ban, CircleCheck, Clock, Hourglass, LoaderCircle, XCircle } from 'lucide-react';
import { Link } from 'react-router';
import type { AlertStatus, OrderSide, OrderStatus } from '../api/types';
import { signedMoney, signedPercent, trendClass } from '../lib/format';
import { Badge, cx } from './ui';

const statusStyles: Record<OrderStatus, { cls: string; icon: typeof Clock; hint: string }> = {
  PENDING: { cls: 'bg-amber-50 text-amber-700 ring-1 ring-amber-200', icon: LoaderCircle, hint: 'Funds/position reservation in progress' },
  OPEN: { cls: 'bg-sky-50 text-sky-700 ring-1 ring-sky-200', icon: Clock, hint: 'Resting limit order; fills when the simulated price reaches the limit' },
  FILLED: { cls: 'bg-emerald-50 text-emerald-700 ring-1 ring-emerald-200', icon: CircleCheck, hint: 'Executed in full' },
  REJECTED: { cls: 'bg-rose-50 text-rose-700 ring-1 ring-rose-200', icon: XCircle, hint: 'Rejected by risk checks' },
  CANCELLED: { cls: 'bg-slate-100 text-slate-600 ring-1 ring-slate-200', icon: Ban, hint: 'Cancelled; reservation released' },
  EXPIRED: { cls: 'bg-slate-100 text-slate-600 ring-1 ring-slate-200', icon: Hourglass, hint: 'Limit order time-to-live elapsed' },
};

export function OrderStatusBadge({ status }: { status: OrderStatus }) {
  const s = statusStyles[status];
  const Icon = s.icon;
  return (
    <Badge className={s.cls}>
      <Icon className={cx('size-3', status === 'PENDING' && 'animate-spin')} aria-hidden />
      <span title={s.hint}>{status}</span>
    </Badge>
  );
}

export function AlertStatusBadge({ status }: { status: AlertStatus }) {
  const cls = status === 'ACTIVE' ? 'bg-sky-50 text-sky-700 ring-1 ring-sky-200'
    : status === 'TRIGGERED' ? 'bg-amber-50 text-amber-800 ring-1 ring-amber-200'
      : 'bg-slate-100 text-slate-600 ring-1 ring-slate-200';
  return <Badge className={cls}>{status}</Badge>;
}

export function SideBadge({ side }: { side: OrderSide }) {
  return (
    <span className={cx('text-xs font-semibold', side === 'BUY' ? 'text-emerald-600' : 'text-rose-600')}>{side}</span>
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

export function SymbolLink({ symbol, name }: { symbol: string; name?: string }) {
  return (
    <Link to={`/demo/market/${encodeURIComponent(symbol)}`} className="group block">
      <span className="font-semibold text-slate-900 group-hover:text-sky-700">{symbol}</span>
      {name && <span className="block truncate text-xs text-slate-500">{name}</span>}
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
