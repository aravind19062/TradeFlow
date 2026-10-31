import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { ChevronLeft } from 'lucide-react';
import { orderApi } from '../api/endpoints';
import { TERMINAL_STATUSES } from '../api/types';
import { CancelOrderButton } from '../components/CancelOrderButton';
import { OrderStatusBadge, SideBadge } from '../components/trading';
import { Card, EmptyState, ErrorState, PageHeader, Spinner } from '../components/ui';
import { dateTime, money, quantity } from '../lib/format';
import { useApi } from '../lib/useApi';

export function OrderDetailPage() {
  const { id = '' } = useParams();
  const [settled, setSettled] = useState(false);
  // Poll only while the order can still change.
  const detail = useApi(() => orderApi.get(id), [id], { topics: ['orders'], intervalMs: settled ? undefined : 3_000 });
  const isTerminal = detail.data ? TERMINAL_STATUSES.includes(detail.data.order.status) : false;
  if (isTerminal !== settled) setSettled(isTerminal);

  const back = (
    <Link to="/orders" className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-800">
      <ChevronLeft className="size-4" /> Orders
    </Link>
  );
  if (detail.error) {
    return <>{back}<Card>{detail.error.status === 404 || detail.error.status === 400
      ? <EmptyState title="Order not found">It doesn't exist or belongs to another account.</EmptyState>
      : <ErrorState error={detail.error} onRetry={detail.reload} />}</Card></>;
  }
  if (!detail.data) return <>{back}<Spinner /></>;

  const { order: o, history, trades } = detail.data;

  return (
    <>
      {back}
      <PageHeader
        title={`${o.side} ${quantity(o.quantity)} ${o.symbol}`}
        subtitle={<span className="font-mono text-xs">{o.id}</span>}
        actions={<><OrderStatusBadge status={o.status} /><CancelOrderButton order={o} size="md" /></>}
      />
      <div className="grid gap-6 lg:grid-cols-3">
        <Card title="Order" className="lg:col-span-1">
          <dl className="space-y-2.5 text-sm">
            <Row label="Instrument" value={<Link to={`/market/${o.symbol}`} className="text-sky-700 hover:underline">{o.symbol}</Link>} />
            <Row label="Side" value={<SideBadge side={o.side} />} />
            <Row label="Type" value={o.type} />
            <Row label="Quantity" value={quantity(o.quantity)} />
            <Row label="Limit price" value={o.limitPrice != null ? money(o.limitPrice) : '—'} />
            <Row label="Reservation price" value={money(o.reservationPrice)} />
            <Row label="Filled" value={`${quantity(o.filledQuantity)} / ${quantity(o.quantity)}`} />
            <Row label="Average fill" value={o.averageFillPrice != null ? money(o.averageFillPrice) : '—'} />
            {o.rejectCode && <Row label="Reject code" value={<span className="text-rose-600">{o.rejectCode}</span>} />}
            <Row label="Client order id" value={<span className="break-all font-mono text-xs">{o.clientOrderId ?? '—'}</span>} />
            <Row label="Placed" value={dateTime(o.createdAt)} />
            <Row label="Updated" value={dateTime(o.updatedAt)} />
          </dl>
          {!isTerminal && <p className="mt-4 text-xs text-slate-400">Refreshing every few seconds while the order is working.</p>}
        </Card>

        <div className="space-y-6 lg:col-span-2">
          <Card title="Status history">
            <ol className="relative space-y-5 border-l border-slate-200 pl-6">
              {history.map((h, i) => (
                <li key={i} className="relative">
                  <span className="absolute -left-[29px] top-1 size-2.5 rounded-full bg-slate-900 ring-4 ring-white" />
                  <div className="flex flex-wrap items-center gap-2">
                    {h.from && <><OrderStatusBadge status={h.from} /><span className="text-slate-400">→</span></>}
                    <OrderStatusBadge status={h.to} />
                    <span className="text-xs text-slate-500">{dateTime(h.at)}</span>
                  </div>
                  {h.reason && <p className="mt-1 text-sm text-slate-600">{h.reason}</p>}
                </li>
              ))}
            </ol>
          </Card>
          <Card title="Executions" bodyClassName="p-0">
            {trades.length === 0 ? <EmptyState title="No executions">{o.status === 'OPEN' ? 'The order is resting until the simulated price reaches the limit.' : undefined}</EmptyState>
              : <table className="w-full text-sm">
                <thead className="bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500">
                  <tr><th className="px-5 py-2.5 font-medium">Executed</th><th className="px-3 py-2.5 text-right font-medium">Qty</th><th className="px-3 py-2.5 text-right font-medium">Price</th><th className="px-5 py-2.5 text-right font-medium">Value</th></tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {trades.map((t) => (
                    <tr key={t.id}>
                      <td className="px-5 py-3 text-slate-500">{dateTime(t.executedAt)}</td>
                      <td className="px-3 py-3 text-right">{quantity(t.quantity)}</td>
                      <td className="px-3 py-3 text-right">{money(t.price)}</td>
                      <td className="px-5 py-3 text-right font-medium">{money(t.grossAmount)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>}
          </Card>
        </div>
      </div>
    </>
  );
}

function Row({ label, value }: { label: string; value: React.ReactNode }) {
  return <div className="flex justify-between gap-4"><dt className="text-slate-500">{label}</dt><dd className="text-right font-medium tabular-nums text-slate-900">{value}</dd></div>;
}
