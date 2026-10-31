import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { ApiError } from '../api/client';
import { orderApi } from '../api/endpoints';
import { TERMINAL_STATUSES, type OrderResponse, type OrderSide, type OrderType, type QuoteResponse } from '../api/types';
import { invalidate } from '../lib/useApi';
import { money, quantity as fmtQty } from '../lib/format';
import { Alert, Button, ConfirmDialog, Segmented, TextInput, useToast } from './ui';
import { OrderStatusBadge } from './trading';

/** Idempotency key (backend: ^[A-Za-z0-9_-]+$, max 64). One per confirmed submission. */
function newClientOrderId(): string {
  const raw = typeof crypto !== 'undefined' && 'randomUUID' in crypto
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`;
  return `web-${raw}`;
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

/**
 * New orders come back 202 PENDING while portfolio-service reserves funds/shares
 * asynchronously. Poll the order until it leaves PENDING (or give up and let the
 * orders page show it later).
 */
async function waitForSettlement(order: OrderResponse, timeoutMs = 15_000): Promise<OrderResponse> {
  let current = order;
  const deadline = Date.now() + timeoutMs;
  while (current.status === 'PENDING' && Date.now() < deadline) {
    await sleep(600);
    current = (await orderApi.get(order.id)).order;
  }
  return current;
}

interface Props {
  quote: QuoteResponse;
  /** Shares the user holds (from the portfolio summary), for SELL guidance. */
  heldQuantity: number;
  reservedQuantity: number;
  availableCash: number | null;
  defaultSide?: OrderSide;
}

export function OrderTicket({ quote, heldQuantity, reservedQuantity, availableCash, defaultSide = 'BUY' }: Props) {
  const toast = useToast();
  const [side, setSide] = useState<OrderSide>(defaultSide);
  const [type, setType] = useState<OrderType>('MARKET');
  const [qty, setQty] = useState('');
  const [limit, setLimit] = useState('');
  const [errors, setErrors] = useState<{ qty?: string; limit?: string }>({});
  const [confirming, setConfirming] = useState<{ clientOrderId: string } | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [result, setResult] = useState<OrderResponse | null>(null);

  const quantity = Number(qty);
  const limitPrice = Number(limit);
  const priceForEstimate = type === 'MARKET' ? quote.price : limitPrice;
  const estimate = quantity > 0 && priceForEstimate > 0 ? quantity * priceForEstimate : null;
  const sellable = Math.max(0, heldQuantity - reservedQuantity);

  function validate(): boolean {
    const next: typeof errors = {};
    if (!/^\d+$/.test(qty) || quantity <= 0) next.qty = 'Enter a whole number of shares greater than 0';
    if (type === 'LIMIT') {
      if (!/^\d+(\.\d{1,2})?$/.test(limit) || limitPrice < 0.01) next.limit = 'Enter a price of at least 0.01 with up to 2 decimals';
    }
    setErrors(next);
    return Object.keys(next).length === 0;
  }

  function onReview(e: FormEvent) {
    e.preventDefault();
    setResult(null);
    if (!validate()) return;
    setSubmitError(null);
    setConfirming({ clientOrderId: newClientOrderId() });
  }

  async function onConfirm() {
    if (!confirming) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const placed = await orderApi.place({
        symbol: quote.symbol, side, type, quantity,
        ...(type === 'LIMIT' ? { limitPrice } : {}),
        clientOrderId: confirming.clientOrderId,
      });
      setConfirming(null);
      setResult(placed);
      const settled = await waitForSettlement(placed);
      setResult(settled);
      invalidate('orders', 'portfolio');
      if (settled.status === 'REJECTED') toast('error', `Order rejected: ${settled.statusReason ?? settled.rejectCode}`);
      else if (settled.status === 'FILLED') toast('success', `${side} ${fmtQty(quantity)} ${quote.symbol} filled at ${money(settled.averageFillPrice)}`);
      else if (settled.status === 'OPEN') toast('info', `Limit order is OPEN at ${money(settled.limitPrice)}`);
      else toast('info', `Order ${settled.status}`);
      if (TERMINAL_STATUSES.includes(settled.status) || settled.status === 'OPEN') { setQty(''); setLimit(''); }
    } catch (e) {
      // Same clientOrderId is kept, so pressing "Place order" again cannot create a duplicate.
      const err = e instanceof ApiError ? e : null;
      const field = err?.fieldErrors.map((f) => `${f.field}: ${f.message}`).join('; ');
      setSubmitError(field || (err?.message ?? 'Order could not be placed'));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div>
      <form onSubmit={onReview} className="space-y-4" noValidate aria-label="Order ticket">
        <Segmented label="Side" value={side} onChange={(v) => { setSide(v); setResult(null); }} options={[
          { value: 'BUY', label: 'Buy', activeClass: 'bg-emerald-600 text-white shadow-sm' },
          { value: 'SELL', label: 'Sell', activeClass: 'bg-rose-600 text-white shadow-sm' },
        ]} />
        <Segmented label="Order type" value={type} onChange={(v) => { setType(v); setErrors({}); }} options={[
          { value: 'MARKET', label: 'Market' },
          { value: 'LIMIT', label: 'Limit' },
        ]} />
        <TextInput
          label="Quantity (shares)" inputMode="numeric" value={qty} onChange={(e) => setQty(e.target.value.trim())}
          placeholder="0" error={errors.qty} name="quantity"
          hint={side === 'SELL' ? `You can sell up to ${fmtQty(sellable)} (${fmtQty(heldQuantity)} held${reservedQuantity ? `, ${fmtQty(reservedQuantity)} reserved` : ''})` : undefined}
        />
        {type === 'LIMIT' && (
          <TextInput
            label="Limit price (₹)" inputMode="decimal" value={limit} onChange={(e) => setLimit(e.target.value.trim())}
            placeholder={quote.price.toFixed(2)} error={errors.limit} name="limitPrice"
            hint={side === 'BUY' ? 'Fills when the simulated price is at or below this price' : 'Fills when the simulated price is at or above this price'}
          />
        )}
        <dl className="space-y-1.5 rounded-lg bg-slate-50 p-3 text-sm">
          <div className="flex justify-between"><dt className="text-slate-500">Last price</dt><dd className="tabular-nums">{money(quote.price)}</dd></div>
          <div className="flex justify-between">
            <dt className="text-slate-500">{type === 'MARKET' ? 'Estimated value' : 'Order value'}</dt>
            <dd className="font-medium tabular-nums" data-testid="order-estimate">{money(estimate)}</dd>
          </div>
          {side === 'BUY' && (
            <div className="flex justify-between"><dt className="text-slate-500">Available cash</dt><dd className="tabular-nums">{money(availableCash)}</dd></div>
          )}
        </dl>
        <Button type="submit" variant={side === 'BUY' ? 'buy' : 'sell'} className="w-full">
          Review {side === 'BUY' ? 'buy' : 'sell'} order
        </Button>
      </form>

      {result && (
        <div className="mt-4" data-testid="order-result">
          <Alert tone={result.status === 'REJECTED' ? 'error' : result.status === 'FILLED' ? 'success' : 'info'}
            title={`Order ${result.status === 'PENDING' ? 'accepted' : result.status.toLowerCase()}`}>
            <div className="mt-1 flex flex-wrap items-center gap-2">
              <OrderStatusBadge status={result.status} />
              <span>{result.statusReason ?? 'Awaiting reservation…'}</span>
            </div>
            <Link to={`/demo/orders/${result.id}`} className="mt-1 inline-block text-xs font-medium underline">View order details</Link>
          </Alert>
        </div>
      )}

      <ConfirmDialog
        open={confirming != null} title="Confirm order" busy={submitting} error={submitError}
        variant={side === 'BUY' ? 'buy' : 'sell'} confirmLabel={`Place ${side.toLowerCase()} order`}
        onConfirm={onConfirm} onCancel={() => { setConfirming(null); setSubmitError(null); }}
      >
        <dl className="space-y-2">
          <Row label="Instrument" value={`${quote.symbol} · ${quote.name}`} />
          <Row label="Side" value={<span className={side === 'BUY' ? 'font-semibold text-emerald-600' : 'font-semibold text-rose-600'}>{side}</span>} />
          <Row label="Type" value={type} />
          <Row label="Quantity" value={fmtQty(quantity)} />
          {type === 'LIMIT' && <Row label="Limit price" value={money(limitPrice)} />}
          <Row label={type === 'MARKET' ? 'Estimated value' : 'Order value'} value={money(estimate)} />
        </dl>
        <p className="mt-3 text-xs text-slate-500">
          {type === 'MARKET'
            ? 'Market orders fill at the latest simulated price when TradeFlow accepts the order; if the price ticks before then, the fill differs from this estimate.'
            : 'Limit orders stay OPEN until the simulated price reaches your limit, you cancel, or they expire.'}
        </p>
      </ConfirmDialog>
    </div>
  );
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return <div className="flex justify-between gap-4"><dt className="text-slate-500">{label}</dt><dd className="text-right font-medium tabular-nums text-slate-900">{value}</dd></div>;
}
