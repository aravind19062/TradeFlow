import { useState } from 'react';
import {
  Area, AreaChart, Cell, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import type { AllocationSlice, PerformancePoint, PricePoint } from '../api/types';
import { cx } from './ui';
import { money, moneyCompact, percent, shortDate, time } from '../lib/format';

/** Shared tooltip look: a dark glass card. */
const tooltipStyle = {
  contentStyle: {
    borderRadius: 12, border: '1px solid rgba(148,163,184,0.2)', fontSize: 12,
    background: 'rgba(15,23,42,0.92)', color: '#e2e8f0', boxShadow: '0 12px 32px -8px rgba(15,23,42,0.45)', padding: '8px 12px',
  },
  labelStyle: { color: '#94a3b8', marginBottom: 2 },
  itemStyle: { color: '#f8fafc', fontWeight: 600 },
};
const cursor = { stroke: '#94a3b8', strokeWidth: 1, strokeDasharray: '4 4' };

const UP = '#059669';
const DOWN = '#dc2626';

function trendColor(first?: number, last?: number) {
  return first == null || last == null || last >= first ? UP : DOWN;
}

export function PerformanceChart({ points, height = 260 }: { points: PerformancePoint[]; height?: number }) {
  const data = points.map((p) => ({ t: new Date(p.at).getTime(), value: p.totalValue, cash: p.cash, holdings: p.holdingsValue }));
  const color = trendColor(data[0]?.value, data.at(-1)?.value);
  return (
    <div style={{ height }} data-testid="performance-chart">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
          <defs>
            <linearGradient id="perfFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={color} stopOpacity={0.3} />
              <stop offset="100%" stopColor={color} stopOpacity={0} />
            </linearGradient>
          </defs>
          <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']}
            tickFormatter={(t: number) => shortDate(new Date(t).toISOString())}
            tick={{ fontSize: 11, fill: '#64748b' }} axisLine={false} tickLine={false} minTickGap={40} />
          <YAxis domain={['auto', 'auto']} tickFormatter={(v: number) => moneyCompact(v)} width={72}
            tick={{ fontSize: 11, fill: '#64748b' }} axisLine={false} tickLine={false} />
          <Tooltip
            labelFormatter={(t) => new Date(Number(t)).toLocaleString('en-IN')}
            formatter={(v, name) => [money(Number(v)), name === 'value' ? 'Total value' : String(name)]}
            {...tooltipStyle} cursor={cursor}
          />
          <Area type="monotone" dataKey="value" stroke={color} strokeWidth={2.25} fill="url(#perfFill)"
            animationDuration={900} animationEasing="ease-out"
            activeDot={{ r: 5, strokeWidth: 3, stroke: '#fff', fill: color }} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

export function PriceChart({ points, height = 280 }: { points: PricePoint[]; height?: number }) {
  const data = points.map((p) => ({ t: new Date(p.asOf).getTime(), price: p.price, tick: p.tick }));
  const color = trendColor(data[0]?.price, data.at(-1)?.price);
  return (
    <div style={{ height }} data-testid="price-chart">
      <ResponsiveContainer width="100%" height="100%">
        <AreaChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
          <defs>
            <linearGradient id="priceFill" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={color} stopOpacity={0.28} />
              <stop offset="100%" stopColor={color} stopOpacity={0} />
            </linearGradient>
          </defs>
          <XAxis dataKey="t" type="number" scale="time" domain={['dataMin', 'dataMax']}
            tickFormatter={(t: number) => time(new Date(t).toISOString()).slice(0, 5)}
            tick={{ fontSize: 11, fill: '#64748b' }} axisLine={false} tickLine={false} minTickGap={40} />
          <YAxis domain={['auto', 'auto']} width={72} tickFormatter={(v: number) => money(v).replace('.00', '')}
            tick={{ fontSize: 11, fill: '#64748b' }} axisLine={false} tickLine={false} />
          <Tooltip
            labelFormatter={(t) => new Date(Number(t)).toLocaleString('en-IN')}
            formatter={(v) => [money(Number(v)), 'Price']}
            {...tooltipStyle} cursor={cursor}
          />
          <Area type="linear" dataKey="price" stroke={color} strokeWidth={2} fill="url(#priceFill)" isAnimationActive={false}
            activeDot={{ r: 5, strokeWidth: 3, stroke: '#fff', fill: color }} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

const PALETTE = ['#4f46e5', '#0ea5e9', '#14b8a6', '#f59e0b', '#a855f7', '#f43f5e', '#64748b', '#84cc16', '#cbd5e1'];

export function AllocationChart({ slices, height = 220 }: { slices: AllocationSlice[]; height?: number }) {
  const data = slices.filter((s) => s.value > 0);
  const [active, setActive] = useState<number | null>(null);
  return (
    <div className="flex flex-col items-center gap-4 sm:flex-row">
      <div style={{ height, width: height }} className="shrink-0">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie data={data} dataKey="value" nameKey="name" innerRadius="60%" outerRadius="94%" paddingAngle={2} cornerRadius={4}
              stroke="none" animationDuration={800} onMouseLeave={() => setActive(null)}>
              {data.map((s, i) => (
                <Cell key={s.name} fill={PALETTE[i % PALETTE.length]} onMouseEnter={() => setActive(i)}
                  style={{ transition: 'opacity 0.2s', opacity: active == null || active === i ? 1 : 0.35, outline: 'none' }} />
              ))}
            </Pie>
            <Tooltip formatter={(v, n) => [money(Number(v)), String(n)]} {...tooltipStyle} />
          </PieChart>
        </ResponsiveContainer>
      </div>
      <ul className="w-full space-y-1.5 text-sm">
        {data.map((s, i) => (
          <li key={s.name} onMouseEnter={() => setActive(i)} onMouseLeave={() => setActive(null)}
            className={cx('flex cursor-default items-center justify-between gap-3 rounded-md px-1.5 py-0.5 transition-all',
              active === i ? 'bg-slate-100' : active != null && 'opacity-50')}>
            <span className="flex min-w-0 items-center gap-2">
              <span className="size-2.5 shrink-0 rounded-sm" style={{ background: PALETTE[i % PALETTE.length] }} />
              <span className="truncate text-slate-700">{s.name}</span>
            </span>
            <span className="tabular-nums text-slate-500">{percent(s.weight, 1)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}

/** Tiny inline trend line for tables. */
export function Sparkline({ values, width = 96, height = 28 }: { values: number[]; width?: number; height?: number }) {
  if (values.length < 2) return <span className="text-xs text-slate-300">—</span>;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = max - min || 1;
  const step = width / (values.length - 1);
  const d = values.map((v, i) => `${i === 0 ? 'M' : 'L'}${(i * step).toFixed(1)},${(height - ((v - min) / span) * (height - 2) - 1).toFixed(1)}`).join(' ');
  return (
    <svg width={width} height={height} aria-hidden className="overflow-visible">
      <path d={`${d} L${width},${height} L0,${height} Z`} fill={trendColor(values[0], values.at(-1))} opacity={0.08} />
      <path d={d} fill="none" stroke={trendColor(values[0], values.at(-1))} strokeWidth={1.5} strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}
