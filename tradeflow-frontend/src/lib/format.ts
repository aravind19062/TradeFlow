// Display formatting. The backend's only currency is INR (instrument.currency / summary.currency).

const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', minimumFractionDigits: 2, maximumFractionDigits: 2 });
const inrCompact = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', notation: 'compact', maximumFractionDigits: 2 });
const num = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 2 });
const int = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 0 });

export function money(value: number | null | undefined, currency = 'INR'): string {
  if (value == null || Number.isNaN(value)) return '—';
  if (currency !== 'INR') {
    return new Intl.NumberFormat('en-IN', { style: 'currency', currency, minimumFractionDigits: 2 }).format(value);
  }
  return inr.format(value);
}

export function moneyCompact(value: number | null | undefined): string {
  return value == null ? '—' : inrCompact.format(value);
}

/** Signed money: "+₹1,234.00" / "−₹56.00". */
export function signedMoney(value: number | null | undefined): string {
  if (value == null) return '—';
  if (value === 0) return money(0);
  return (value > 0 ? '+' : '−') + money(Math.abs(value));
}

/** Ratio (0.0123) as percent "1.23%". */
export function percent(ratio: number | null | undefined, digits = 2): string {
  if (ratio == null || Number.isNaN(ratio)) return '—';
  return `${(ratio * 100).toFixed(digits)}%`;
}

export function signedPercent(ratio: number | null | undefined, digits = 2): string {
  if (ratio == null || Number.isNaN(ratio)) return '—';
  const s = (Math.abs(ratio) * 100).toFixed(digits);
  return ratio > 0 ? `+${s}%` : ratio < 0 ? `−${s}%` : `${s}%`;
}

export function number(value: number | null | undefined): string {
  return value == null ? '—' : num.format(value);
}

export function quantity(value: number | null | undefined): string {
  return value == null ? '—' : int.format(value);
}

export function dateTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' });
}

export function time(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

export function shortDate(iso: string): string {
  return new Date(iso).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' });
}

/** Tailwind text colour for a signed value: green up, red down, neutral at zero/unknown. */
export function trendClass(value: number | null | undefined): string {
  if (value == null || value === 0) return 'text-slate-500';
  return value > 0 ? 'text-emerald-600' : 'text-rose-600';
}

export function shortId(id: string): string {
  return id.slice(0, 8);
}
