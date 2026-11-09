import { useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { liveApi } from '../api/endpoints';
import { Badge, Button, Card, EmptyState, ErrorState, Spinner, Tabs } from '../components/ui';
import { dateTime, money, signedMoney, trendClass } from '../lib/format';
import { useApi } from '../lib/useApi';

type Tab = 'holdings' | 'positions' | 'orders';

const th = 'px-3 py-2.5 font-medium';
const td = 'px-3 py-2.5';

/**
 * The linked broker account, read-only: funds, holdings, positions and the broker's order book.
 * No polling: brokers rate-limit their APIs and may block an app that keeps exceeding them, so
 * data is fetched when a tab opens and on Refresh.
 */
export function BrokerAccountCard() {
  const [tab, setTab] = useState<Tab>('holdings');
  const [version, setVersion] = useState(0);
  const funds = useApi(() => liveApi.brokerFunds(), [version]);
  return (
    <Card className="mt-6" title="Your broker account (read-only)" bodyClassName="p-0"
      action={<Button variant="secondary" size="sm" onClick={() => setVersion((v) => v + 1)} loading={funds.refreshing}>
        <RefreshCw className="size-3.5" /> Refresh
      </Button>}>
      <div className="grid gap-4 border-b border-slate-100 p-5 sm:grid-cols-3" data-testid="broker-funds">
        {funds.error ? <div className="sm:col-span-3"><ErrorState error={funds.error} onRetry={funds.reload} compact /></div>
          : !funds.data ? <div className="sm:col-span-3"><Spinner /></div>
            : <>
              <div><p className="text-xs uppercase tracking-wider text-slate-500">Available</p>
                <p className="mt-1 text-xl font-semibold tabular-nums">{money(funds.data.available)}</p></div>
              <div><p className="text-xs uppercase tracking-wider text-slate-500">Used margin</p>
                <p className="mt-1 text-xl font-semibold tabular-nums">{money(funds.data.used)}</p></div>
              <div><p className="text-xs uppercase tracking-wider text-slate-500">As of</p>
                <p className="mt-1 text-sm text-slate-600">{dateTime(funds.data.asOf)}</p></div>
            </>}
      </div>
      <div className="px-5 pt-3">
        <Tabs value={tab} onChange={setTab} tabs={[
          { value: 'holdings', label: 'Holdings' }, { value: 'positions', label: 'Positions' }, { value: 'orders', label: 'Broker orders' },
        ]} />
      </div>
      {tab === 'holdings' && <Holdings version={version} />}
      {tab === 'positions' && <Positions version={version} />}
      {tab === 'orders' && <Orders version={version} />}
    </Card>
  );
}

function Holdings({ version }: { version: number }) {
  const q = useApi(() => liveApi.brokerHoldings(), [version]);
  if (q.error) return <ErrorState error={q.error} onRetry={q.reload} />;
  if (!q.data) return <Spinner />;
  if (q.data.length === 0) return <EmptyState title="No holdings at your broker" />;
  return (
    <div className="overflow-x-auto pb-2">
      <table className="w-full text-sm" data-testid="broker-holdings">
        <thead className="text-left text-xs uppercase tracking-wide text-slate-500">
          <tr><th className={`${th} pl-5`}>Instrument</th><th className={`${th} text-right`}>Qty</th><th className={`${th} text-right`}>Avg cost</th>
            <th className={`${th} text-right`}>Last price</th><th className={`${th} pr-5 text-right`}>P&amp;L</th></tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {q.data.map((h) => {
            const pnl = h.averagePrice != null && h.lastPrice != null ? (h.lastPrice - h.averagePrice) * h.quantity : null;
            return (
              <tr key={`${h.exchange}:${h.symbol}`} className="transition-colors hover:bg-slate-50">
                <td className={`${td} pl-5 font-semibold`}>{h.symbol} <span className="text-xs font-normal text-slate-400">{h.exchange}</span></td>
                <td className={`${td} text-right`}>{h.quantity}</td>
                <td className={`${td} text-right`}>{money(h.averagePrice)}</td>
                <td className={`${td} text-right`}>{money(h.lastPrice)}</td>
                <td className={`${td} pr-5 text-right ${trendClass(pnl)}`}>{pnl == null ? '—' : signedMoney(pnl)}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

function Positions({ version }: { version: number }) {
  const q = useApi(() => liveApi.brokerPositions(), [version]);
  if (q.error) return <ErrorState error={q.error} onRetry={q.reload} />;
  if (!q.data) return <Spinner />;
  if (q.data.length === 0) return <EmptyState title="No open positions at your broker" />;
  return (
    <div className="overflow-x-auto pb-2">
      <table className="w-full text-sm" data-testid="broker-positions">
        <thead className="text-left text-xs uppercase tracking-wide text-slate-500">
          <tr><th className={`${th} pl-5`}>Instrument</th><th className={th}>Product</th><th className={`${th} text-right`}>Net qty</th>
            <th className={`${th} text-right`}>Avg price</th><th className={`${th} pr-5 text-right`}>P&amp;L</th></tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {q.data.map((p) => (
            <tr key={`${p.exchange}:${p.symbol}:${p.product ?? ''}`} className="transition-colors hover:bg-slate-50">
              <td className={`${td} pl-5 font-semibold`}>{p.symbol} <span className="text-xs font-normal text-slate-400">{p.exchange}</span></td>
              <td className={td}>{p.product ? <Badge className={p.product === 'INTRADAY' ? 'bg-amber-50 text-amber-800 ring-1 ring-amber-200' : 'bg-slate-100 text-slate-600'}>{p.product}</Badge> : '—'}</td>
              <td className={`${td} text-right`}>{p.quantity}</td>
              <td className={`${td} text-right`}>{money(p.averagePrice)}</td>
              <td className={`${td} pr-5 text-right ${trendClass(p.pnl)}`}>{p.pnl == null ? '—' : signedMoney(p.pnl)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Orders({ version }: { version: number }) {
  const q = useApi(() => liveApi.brokerOrders(), [version]);
  if (q.error) return <ErrorState error={q.error} onRetry={q.reload} />;
  if (!q.data) return <Spinner />;
  if (q.data.length === 0) return <EmptyState title="No orders at your broker today" />;
  return (
    <div className="overflow-x-auto pb-2">
      <table className="w-full text-sm" data-testid="broker-orders">
        <thead className="text-left text-xs uppercase tracking-wide text-slate-500">
          <tr><th className={`${th} pl-5`}>Instrument</th><th className={th}>Side</th><th className={th}>Type</th>
            <th className={`${th} text-right`}>Filled / qty</th><th className={`${th} text-right`}>Price</th><th className={th}>Status</th>
            <th className={`${th} pr-5 text-right`}>Placed</th></tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {q.data.map((o) => (
            <tr key={o.brokerOrderId} className="transition-colors hover:bg-slate-50">
              <td className={`${td} pl-5 font-semibold`}>{o.symbol} <span className="text-xs font-normal text-slate-400">{o.exchange}</span></td>
              <td className={`${td} text-xs font-semibold ${o.side === 'SELL' ? 'text-rose-600' : 'text-emerald-600'}`}>{o.side}</td>
              <td className={`${td} text-xs text-slate-500`}>{o.type}</td>
              <td className={`${td} text-right`}>{o.filledQuantity} / {o.quantity}</td>
              <td className={`${td} text-right`}>{money(o.averagePrice ?? o.price)}</td>
              <td className={td}>
                <Badge className="bg-slate-100 text-slate-700 ring-1 ring-slate-200">{o.status}</Badge>
                {o.message && <span className="block text-xs text-slate-500">{o.message}</span>}
              </td>
              <td className={`${td} pr-5 text-right text-slate-500`}>{o.updatedAt ? dateTime(o.updatedAt) : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
