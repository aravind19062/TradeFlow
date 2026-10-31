import { useMemo } from 'react';
import { Link, useSearchParams } from 'react-router';
import { ChevronLeft, ChevronRight, X } from 'lucide-react';
import { orderApi } from '../api/endpoints';
import type { OrderResponse, OrderSide, OrderStatus, PageResponse } from '../api/types';
import { CancelOrderButton } from '../components/CancelOrderButton';
import { OrderStatusBadge, SideBadge } from '../components/trading';
import { Button, Card, EmptyState, ErrorState, PageHeader, Spinner, Tabs } from '../components/ui';
import { dateTime, money, quantity, shortId } from '../lib/format';
import { useApi } from '../lib/useApi';

type Tab = 'open' | 'history' | 'trades';
const STATUSES: OrderStatus[] = ['PENDING', 'OPEN', 'FILLED', 'REJECTED', 'CANCELLED', 'EXPIRED'];
const PAGE_SIZE = 20;

export function OrdersPage() {
  const [params, setParams] = useSearchParams();
  const tab = (params.get('tab') as Tab) ?? 'open';
  const symbol = params.get('symbol')?.toUpperCase() || undefined;
  const status = (params.get('status') as OrderStatus) || undefined;
  const side = (params.get('side') as OrderSide) || undefined;
  const page = Number(params.get('page') ?? 0);

  const update = (patch: Record<string, string | undefined>) => {
    const next = new URLSearchParams(params);
    for (const [k, v] of Object.entries(patch)) { if (v) next.set(k, v); else next.delete(k); }
    if (!('page' in patch)) next.delete('page');
    setParams(next, { replace: true });
  };

  // "Open" = still working: OPEN (resting limit) and PENDING (reservation in flight).
  const openOrders = useApi(async () => {
    const [o, p] = await Promise.all([
      orderApi.list({ status: 'OPEN', symbol, size: 100 }),
      orderApi.list({ status: 'PENDING', symbol, size: 100 }),
    ]);
    return [...p.content, ...o.content].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }, [symbol], { topics: ['orders'], intervalMs: 5_000 });
  const history = useApi(() => orderApi.list({ status, side, symbol, page, size: PAGE_SIZE }),
    [status, side, symbol, page], { topics: ['orders'], enabled: tab === 'history', intervalMs: 15_000 });
  const trades = useApi(() => orderApi.trades({ symbol, page, size: PAGE_SIZE }),
    [symbol, page], { topics: ['orders'], enabled: tab === 'trades' });

  const tabs = useMemo(() => [
    { value: 'open' as const, label: 'Open orders', count: openOrders.data?.length },
    { value: 'history' as const, label: 'Order history' },
    { value: 'trades' as const, label: 'Trades' },
  ], [openOrders.data]);

  return (
    <>
      <PageHeader title="Orders" subtitle="Orders are settled asynchronously: PENDING → OPEN/FILLED/REJECTED." />
      <Tabs value={tab} tabs={tabs} onChange={(t) => update({ tab: t === 'open' ? undefined : t })} />

      <div className="mb-4 flex flex-wrap items-center gap-2">
        {symbol && (
          <span className="inline-flex items-center gap-1 rounded-full bg-slate-900 px-3 py-1 text-xs font-medium text-white">
            {symbol}
            <button onClick={() => update({ symbol: undefined })} aria-label="Clear symbol filter"><X className="size-3" /></button>
          </span>
        )}
        {tab === 'history' && <>
          <select aria-label="Status filter" value={status ?? ''} onChange={(e) => update({ status: e.target.value || undefined })}
            className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm">
            <option value="">All statuses</option>
            {STATUSES.map((s) => <option key={s}>{s}</option>)}
          </select>
          <select aria-label="Side filter" value={side ?? ''} onChange={(e) => update({ side: e.target.value || undefined })}
            className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm">
            <option value="">Buy &amp; sell</option>
            <option>BUY</option><option>SELL</option>
          </select>
        </>}
      </div>

      {tab === 'open' && (
        <Card bodyClassName="p-0">
          {openOrders.error ? <ErrorState error={openOrders.error} onRetry={openOrders.reload} />
            : !openOrders.data ? <Spinner />
              : openOrders.data.length === 0 ? <EmptyState title="No open orders">Limit orders rest here until the simulated price reaches the limit.</EmptyState>
                : <OrderTable orders={openOrders.data} showCancel />}
        </Card>
      )}

      {tab === 'history' && (
        <Card bodyClassName="p-0">
          {history.error ? <ErrorState error={history.error} onRetry={history.reload} />
            : !history.data ? <Spinner />
              : history.data.content.length === 0 ? <EmptyState title="No orders match these filters" />
                : <><OrderTable orders={history.data.content} showCancel /><Pager page={history.data} onPage={(p) => update({ page: String(p) })} /></>}
        </Card>
      )}

      {tab === 'trades' && (
        <Card bodyClassName="p-0">
          {trades.error ? <ErrorState error={trades.error} onRetry={trades.reload} />
            : !trades.data ? <Spinner />
              : trades.data.content.length === 0 ? <EmptyState title="No executions yet" />
                : <>
                  <div className="overflow-x-auto">
                    <table className="w-full text-sm" data-testid="trades-table">
                      <thead className="bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500">
                        <tr><th className="px-5 py-2.5 font-medium">Executed</th><th className="px-3 py-2.5 font-medium">Symbol</th><th className="px-3 py-2.5 font-medium">Side</th><th className="px-3 py-2.5 text-right font-medium">Qty</th><th className="px-3 py-2.5 text-right font-medium">Price</th><th className="px-3 py-2.5 text-right font-medium">Value</th><th className="px-5 py-2.5 font-medium">Order</th></tr>
                      </thead>
                      <tbody className="divide-y divide-slate-100">
                        {trades.data.content.map((t) => (
                          <tr key={t.id} className="hover:bg-slate-50">
                            <td className="whitespace-nowrap px-5 py-3 text-slate-500">{dateTime(t.executedAt)}</td>
                            <td className="px-3 py-3"><Link to={`/demo/market/${t.symbol}`} className="font-semibold hover:text-sky-700">{t.symbol}</Link></td>
                            <td className="px-3 py-3"><SideBadge side={t.side} /></td>
                            <td className="px-3 py-3 text-right">{quantity(t.quantity)}</td>
                            <td className="px-3 py-3 text-right">{money(t.price)}</td>
                            <td className="px-3 py-3 text-right font-medium">{money(t.grossAmount)}</td>
                            <td className="px-5 py-3"><Link to={`/demo/orders/${t.orderId}`} className="font-mono text-xs text-sky-700 hover:underline">{shortId(t.orderId)}</Link></td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <Pager page={trades.data} onPage={(p) => update({ page: String(p) })} />
                </>}
        </Card>
      )}
    </>
  );
}

function OrderTable({ orders, showCancel }: { orders: OrderResponse[]; showCancel?: boolean }) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-sm" data-testid="orders-table">
        <thead className="bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500">
          <tr>
            <th className="px-5 py-2.5 font-medium">Placed</th>
            <th className="px-3 py-2.5 font-medium">Symbol</th>
            <th className="px-3 py-2.5 font-medium">Side / type</th>
            <th className="px-3 py-2.5 text-right font-medium">Qty</th>
            <th className="px-3 py-2.5 text-right font-medium">Limit</th>
            <th className="px-3 py-2.5 text-right font-medium">Fill price</th>
            <th className="px-3 py-2.5 font-medium">Status</th>
            <th className="px-5 py-2.5"><span className="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {orders.map((o) => (
            <tr key={o.id} className="hover:bg-slate-50" data-order-id={o.id}>
              <td className="whitespace-nowrap px-5 py-3 text-slate-500"><Link to={`/demo/orders/${o.id}`} className="hover:text-sky-700">{dateTime(o.createdAt)}</Link></td>
              <td className="px-3 py-3"><Link to={`/demo/market/${o.symbol}`} className="font-semibold hover:text-sky-700">{o.symbol}</Link></td>
              <td className="whitespace-nowrap px-3 py-3"><SideBadge side={o.side} /> <span className="text-xs text-slate-500">{o.type}</span></td>
              <td className="px-3 py-3 text-right">{quantity(o.quantity)}</td>
              <td className="px-3 py-3 text-right">{o.limitPrice != null ? money(o.limitPrice) : <span className="text-slate-300">—</span>}</td>
              <td className="px-3 py-3 text-right">{o.averageFillPrice != null ? money(o.averageFillPrice) : <span className="text-slate-300">—</span>}</td>
              <td className="px-3 py-3">
                <OrderStatusBadge status={o.status} />
                {o.status === 'REJECTED' && o.statusReason && <p className="mt-1 max-w-56 text-xs text-rose-600">{o.statusReason}</p>}
              </td>
              <td className="whitespace-nowrap px-5 py-3 text-right">
                {showCancel && <CancelOrderButton order={o} />}
                {o.status === 'PENDING' && <span className="text-xs text-slate-400" title="PENDING orders cannot be cancelled while funds are being reserved">Reserving…</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Pager({ page, onPage }: { page: PageResponse<unknown>; onPage: (p: number) => void }) {
  if (page.totalPages <= 1) return null;
  return (
    <div className="flex items-center justify-between border-t border-slate-100 px-5 py-3 text-sm text-slate-500">
      <span>Page {page.page + 1} of {page.totalPages} · {page.totalElements} total</span>
      <div className="flex gap-2">
        <Button variant="secondary" size="sm" disabled={page.page === 0} onClick={() => onPage(page.page - 1)} aria-label="Previous page"><ChevronLeft className="size-4" /></Button>
        <Button variant="secondary" size="sm" disabled={page.page + 1 >= page.totalPages} onClick={() => onPage(page.page + 1)} aria-label="Next page"><ChevronRight className="size-4" /></Button>
      </div>
    </div>
  );
}
