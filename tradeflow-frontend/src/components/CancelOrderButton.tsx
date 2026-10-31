import { useState } from 'react';
import { ApiError } from '../api/client';
import { orderApi } from '../api/endpoints';
import type { OrderResponse } from '../api/types';
import { invalidate } from '../lib/useApi';
import { money, quantity } from '../lib/format';
import { Button, ConfirmDialog, useToast } from './ui';

/** POST /api/orders/{id}/cancel. Only OPEN orders can be cancelled (PENDING ones are mid-reservation). */
export function CancelOrderButton({ order, size = 'sm' }: { order: OrderResponse; size?: 'sm' | 'md' }) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (order.status !== 'OPEN') return null;

  async function confirm() {
    setBusy(true);
    setError(null);
    try {
      const cancelled = await orderApi.cancel(order.id);
      setOpen(false);
      invalidate('orders', 'portfolio');
      toast('success', `Order ${cancelled.symbol} ${cancelled.side} ${quantity(cancelled.quantity)} cancelled`);
    } catch (e) {
      const err = e instanceof ApiError ? e : null;
      // e.g. 409 INVALID_STATE_TRANSITION when the order filled a moment earlier
      setError(err?.message ?? 'Could not cancel the order');
      invalidate('orders');
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Button variant="secondary" size={size} onClick={() => { setError(null); setOpen(true); }}
        className="border-rose-200 text-rose-700 hover:bg-rose-50" aria-label={`Cancel order ${order.symbol} ${order.side}`}>
        Cancel
      </Button>
      <ConfirmDialog open={open} title="Cancel this order?" confirmLabel="Cancel order" variant="danger" busy={busy} error={error}
        onConfirm={confirm} onCancel={() => setOpen(false)}>
        <p>
          <span className={order.side === 'BUY' ? 'font-semibold text-emerald-600' : 'font-semibold text-rose-600'}>{order.side}</span>{' '}
          {quantity(order.quantity)} {order.symbol} {order.type}{order.limitPrice != null && <> @ {money(order.limitPrice)}</>}
        </p>
        <p className="mt-2 text-slate-500">Its reserved {order.side === 'BUY' ? 'cash' : 'shares'} will be released.</p>
      </ConfirmDialog>
    </>
  );
}
