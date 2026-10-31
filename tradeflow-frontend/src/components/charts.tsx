import {
  Area, AreaChart, Cell, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts';
import type { AllocationSlice, PerformancePoint, PricePoint } from '../api/types';
import { money, moneyCompact, percent, shortDate, time } from '../lib/format';

const UP = '#059669';
const DOWN = '#e11d48';

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
              <stop offset="0%" stopColor={color} stopOpacity={0.25} />
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
            contentStyle={{ borderRadius: 8, border: '1px solid #e2e8f0', fontSize: 12 }}
          />
          <Area type="monotone" dataKey="value" stroke={color} strokeWidth={2} fill="url(#perfFill)" isAnimationActive={false} />
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
              <stop offset="0%" stopColor={color} stopOpacity={0.2} />
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
            contentStyle={{ borderRadius: 8, border: '1px solid #e2e8f0', fontSize: 12 }}
          />
          <Area type="linear" dataKey="price" stroke={color} strokeWidth={2} fill="url(#priceFill)" isAnimationActive={false} />
        </AreaChart>
      </ResponsiveContainer>
    </div>
  );
}

const PALETTE = ['#0f172a', '#0284c7', '#059669', '#d97706', '#7c3aed', '#db2777', '#0891b2', '#65a30d', '#94a3b8'];

export function AllocationChart({ slices, height = 220 }: { slices: AllocationSlice[]; height?: number }) {
  const data = slices.filter((s) => s.value > 0);
  return (
    <div className="flex flex-col items-center gap-4 sm:flex-row">
      <div style={{ height, width: height }} className="shrink-0">
        <ResponsiveContainer width="100%" height="100%">
          <PieChart>
            <Pie data={data} dataKey="value" nameKey="name" innerRadius="58%" outerRadius="95%" paddingAngle={1} isAnimationActive={false}>
              {data.map((s, i) => <Cell key={s.name} fill={PALETTE[i % PALETTE.length]} />)}
            </Pie>
            <Tooltip formatter={(v, n) => [money(Number(v)), String(n)]} contentStyle={{ borderRadius: 8, fontSize: 12 }} />
          </PieChart>
        </ResponsiveContainer>
      </div>
      <ul className="w-full space-y-1.5 text-sm">
        {data.map((s, i) => (
          <li key={s.name} className="flex items-center justify-between gap-3">
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
    <svg width={width} height={height} aria-hidden>
      <path d={d} fill="none" stroke={trendColor(values[0], values.at(-1))} strokeWidth={1.5} />
    </svg>
  );
}
