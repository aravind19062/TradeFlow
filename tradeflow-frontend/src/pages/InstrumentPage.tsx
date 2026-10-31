import { useState } from 'react';
import { Link, useParams } from 'react-router';
import { ChevronLeft, Star } from 'lucide-react';
import { alertApi, marketApi, orderApi, watchlistApi } from '../api/endpoints';
import { PriceChart } from '../components/charts';
import { OrderTicket } from '../components/OrderTicket';
import { AlertForm } from '../components/AlertForm';
import { AlertStatusBadge, Change, OrderStatusBadge, SideBadge, SimulatedNote } from '../components/trading';
import { Button, Card, cx, EmptyState, ErrorState, PageHeader, Segmented, Skeleton, Spinner, useToast } from '../components/ui';
import { dateTime, money, percent, quantity, signedMoney, time, trendClass } from '../lib/format';
import { invalidate, useApi } from '../lib/useApi';
import { usePortfolioSummary } from '../lib/usePortfolio';

const RANGES = { '50': '50 ticks', '200': '200 ticks', '1000': '1000 ticks' } as const;
type Range = keyof typeof RANGES;

export function InstrumentPage() {
  const { symbol = '' } = useParams();
  const toast = useToast();
  const [range, setRange] = useState<Range>('200');
  const quote = useApi(() => marketApi.quote(symbol), [symbol], { topics: ['market'], intervalMs: 15_000 });
  const history = useApi(() => marketApi.history(symbol, Number(range)), [symbol, range], { topics: ['market'], intervalMs: 30_000 });
  const summary = usePortfolioSummary();
  const watchlist = useApi(() => watchlistApi.list(), [], { topics: ['watchlist'] });
  const orders = useApi(() => orderApi.list({ symbol, size: 5 }), [symbol], { topics: ['orders'], intervalMs: 15_000 });
  const alerts = useApi(() => alertApi.list(), [], { topics: ['alerts'] });
  const [watchBusy, setWatchBusy] = useState(false);

  if (quote.error) {
    return (
      <>
        <BackLink />
        <Card>{quote.error.status === 404
          ? <EmptyState title={`Unknown instrument "${symbol}"`}><Link to="/demo/market" className="text-sky-700 hover:underline">Back to market watch</Link></EmptyState>
          : <ErrorState error={quote.error} onRetry={quote.reload} />}</Card>
      </>
    );
  }

  const q = quote.data;
  const holding = summary.data?.holdings.find((h) => h.symbol === q?.symbol);
  const isWatched = !!watchlist.data?.some((w) => w.symbol === q?.symbol);
  const symbolAlerts = alerts.data?.filter((a) => a.symbol === q?.symbol) ?? [];
  const points = history.data?.points ?? [];
  const prices = points.map((p) => p.price);

  async function toggleWatch() {
    if (!q) return;
    setWatchBusy(true);
    try {
      if (isWatched) await watchlistApi.remove(q.symbol); else await watchlistApi.add(q.symbol);
      invalidate('watchlist');
      toast('success', isWatched ? `${q.symbol} removed from watchlist` : `${q.symbol} added to watchlist`);
    } catch (e) {
      toast('error', (e as Error).message);
    } finally {
      setWatchBusy(false);
    }
  }

  return (
    <>
      <BackLink />
      {!q ? <Skeleton className="mb-6 h-16 w-72" /> : (
        <PageHeader
          title={`${q.symbol} · ${q.name}`}
          subtitle={<>{q.sector} · {q.assetClass} · {q.currency}</>}
          actions={<Button variant="secondary" size="sm" onClick={toggleWatch} loading={watchBusy}>
            <Star className={cx('size-3.5', isWatched && 'fill-amber-400 text-amber-400')} /> {isWatched ? 'Watching' : 'Watch'}
          </Button>}
        />
      )}

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="space-y-6 lg:col-span-2">
          <Card>
            {q ? (
              <div className="mb-4 flex flex-wrap items-end justify-between gap-4">
                <div>
                  <p className="text-3xl font-semibold tabular-nums tracking-tight" data-testid="instrument-price">{money(q.price)}</p>
                  <p className="mt-1 text-sm"><Change change={q.change} ratio={q.changeRatio} /> <span className="text-slate-400">since previous tick · {time(q.asOf)} · tick {q.tick}</span></p>
                </div>
                <Segmented label="History range" value={range} onChange={setRange}
                  options={(Object.keys(RANGES) as Range[]).map((r) => ({ value: r, label: RANGES[r] }))} />
              </div>
            ) : <Skeleton className="mb-4 h-14 w-56" />}
            {history.error ? <ErrorState error={history.error} onRetry={history.reload} />
              : !history.data ? <Skeleton className="h-[280px]" />
                : points.length < 2 ? <EmptyState title="Not enough price history yet" />
                  : <>
                    <PriceChart points={points} />
                    <div className="mt-3 grid grid-cols-3 gap-4 text-sm">
                      <div><p className="text-xs text-slate-500">Low ({points.length} ticks)</p><p className="font-medium tabular-nums">{money(Math.min(...prices))}</p></div>
                      <div><p className="text-xs text-slate-500">High</p><p className="font-medium tabular-nums">{money(Math.max(...prices))}</p></div>
                      <div><p className="text-xs text-slate-500">Range change</p><Change ratio={(prices.at(-1)! - prices[0]) / prices[0]} className="font-medium" /></div>
                    </div>
                  </>}
          </Card>

          <Card title="Your position">
            {summary.error ? <ErrorState error={summary.error} onRetry={summary.reload} compact />
              : !summary.data ? <Skeleton className="h-16" />
                : !holding ? <p className="text-sm text-slate-500">You don't hold {symbol.toUpperCase()}.</p>
                  : <dl className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-4">
                    <Metric label="Quantity" value={quantity(holding.quantity)} sub={holding.reservedQuantity ? `${quantity(holding.reservedQuantity)} reserved` : undefined} />
                    <Metric label="Average cost" value={money(holding.averageCost)} />
                    <Metric label="Market value" value={money(holding.marketValue)} sub={`${percent(holding.weight, 1)} of portfolio`} />
                    <Metric label="Unrealized P&L" value={<span className={trendClass(holding.unrealizedPnl)}>{signedMoney(holding.unrealizedPnl)}</span>}
                      sub={<Change ratio={holding.unrealizedPnlRatio} />} />
                  </dl>}
          </Card>

          <Card title={`Your ${symbol.toUpperCase()} orders`} bodyClassName="p-0"
            action={<Link to={`/demo/orders?symbol=${encodeURIComponent(symbol)}`} className="text-sm font-medium text-sky-700 hover:underline">All</Link>}>
            {orders.error ? <ErrorState error={orders.error} onRetry={orders.reload} />
              : !orders.data ? <Spinner />
                : orders.data.content.length === 0 ? <EmptyState title="No orders for this instrument" />
                  : <ul className="divide-y divide-slate-100 text-sm">
                    {orders.data.content.map((o) => (
                      <li key={o.id}>
                        <Link to={`/demo/orders/${o.id}`} className="flex items-center justify-between gap-3 px-5 py-3 hover:bg-slate-50">
                          <span><SideBadge side={o.side} /> {quantity(o.quantity)} · {o.type}{o.limitPrice != null && ` @ ${money(o.limitPrice)}`}</span>
                          <span className="flex items-center gap-3 text-slate-500"><span className="hidden sm:inline">{dateTime(o.createdAt)}</span><OrderStatusBadge status={o.status} /></span>
                        </Link>
                      </li>
                    ))}
                  </ul>}
          </Card>
        </div>

        <div className="space-y-6">
          <Card title="Trade">
            {q ? <OrderTicket quote={q} heldQuantity={holding?.quantity ?? 0} reservedQuantity={holding?.reservedQuantity ?? 0}
              availableCash={summary.data?.availableCash ?? null} /> : <Spinner />}
          </Card>
          <Card title="Price alerts">
            {q && <AlertForm symbol={q.symbol} currentPrice={q.price} />}
            {symbolAlerts.length > 0 && (
              <ul className="mt-4 space-y-2 border-t border-slate-100 pt-4 text-sm">
                {symbolAlerts.slice(0, 5).map((a) => (
                  <li key={a.id} className="flex items-center justify-between gap-2">
                    <span className="tabular-nums">{a.direction === 'ABOVE' ? '≥' : '≤'} {money(a.threshold)}</span>
                    <AlertStatusBadge status={a.status} />
                  </li>
                ))}
              </ul>
            )}
            <Link to="/demo/watchlist" className="mt-3 inline-block text-xs font-medium text-sky-700 hover:underline">Manage all alerts</Link>
          </Card>
        </div>
      </div>
      <SimulatedNote className="mt-6" />
    </>
  );
}

function BackLink() {
  return (
    <Link to="/demo/market" className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-800">
      <ChevronLeft className="size-4" /> Market watch
    </Link>
  );
}

function Metric({ label, value, sub }: { label: string; value: React.ReactNode; sub?: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className="mt-0.5 font-semibold tabular-nums text-slate-900">{value}</dd>
      {sub && <dd className="text-xs text-slate-500">{sub}</dd>}
    </div>
  );
}
