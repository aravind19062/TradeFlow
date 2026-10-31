import { Link } from 'react-router';
import { ArrowRight, LoaderCircle, TriangleAlert } from 'lucide-react';
import { orderApi, portfolioApi, watchlistApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { AllocationChart, PerformanceChart } from '../components/charts';
import { Change, OrderStatusBadge, SideBadge, SimulatedNote, SymbolLink } from '../components/trading';
import { Alert, Card, EmptyState, ErrorState, PageHeader, Skeleton, Spinner, Stat } from '../components/ui';
import { dateTime, money, percent, quantity, signedMoney, signedPercent, time, trendClass } from '../lib/format';
import { useApi } from '../lib/useApi';
import { usePortfolioSummary } from '../lib/usePortfolio';

export function DashboardPage() {
  const { user, isNewUser } = useAuth();
  const summary = usePortfolioSummary();
  const ready = summary.data != null;
  const performance = useApi(() => portfolioApi.performance(), [], { topics: ['portfolio'], enabled: ready });
  const risk = useApi(() => portfolioApi.risk(), [], { topics: ['portfolio'], enabled: ready, intervalMs: 30_000 });
  const recent = useApi(() => orderApi.list({ size: 6 }), [], { topics: ['orders'], intervalMs: 15_000 });
  const open = useApi(() => orderApi.list({ status: 'OPEN', size: 1 }), [], { topics: ['orders'], intervalMs: 15_000 });
  const watchlist = useApi(() => watchlistApi.list(), [], { topics: ['watchlist'], intervalMs: 30_000 });

  const s = summary.data;

  return (
    <>
      <PageHeader
        title={`Welcome${isNewUser ? '' : ' back'}, ${user?.name?.split(' ')[0] ?? ''}`}
        subtitle={s?.pricesAsOf ? <>Valued at simulated prices as of {time(s.pricesAsOf)}</> : 'Your portfolio at a glance'}
        actions={<Link to="/market" className="inline-flex items-center gap-1.5 rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800">Trade <ArrowRight className="size-4" /></Link>}
      />

      {summary.opening && (
        <div className="mb-6"><Alert tone="info" title="Opening your trading account">
          <span className="inline-flex items-center gap-2"><LoaderCircle className="size-3.5 animate-spin" /> portfolio-service is creating your cash account. This takes a moment after registration.</span>
        </Alert></div>
      )}
      {summary.error && <Card className="mb-6"><ErrorState error={summary.error} onRetry={summary.reload} /></Card>}
      {s && !s.pricesAvailable && (
        <div className="mb-6"><Alert tone="warning">Market data is unavailable right now; holdings are shown at cost.</Alert></div>
      )}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {s ? <>
          <Stat testId="stat-total-value" label="Portfolio value" value={money(s.totalValue)}
            sub={<>Net deposits {money(s.netDeposits)}</>} />
          <Stat testId="stat-available-cash" label="Available cash" value={money(s.availableCash)}
            sub={s.reservedCash > 0 ? <>{money(s.reservedCash)} reserved for open orders</> : <>Cash balance {money(s.cashBalance)}</>} />
          <Stat testId="stat-total-pnl" label="Total P&L" value={signedMoney(s.totalPnl)} valueClass={trendClass(s.totalPnl)}
            sub={<span className={trendClass(s.totalReturnRatio)}>{signedPercent(s.totalReturnRatio)} return</span>} />
          <Stat testId="stat-unrealized" label="Unrealized / realized" value={<span className={trendClass(s.unrealizedPnl)}>{signedMoney(s.unrealizedPnl)}</span>}
            sub={<>Realized <span className={trendClass(s.realizedPnl)}>{signedMoney(s.realizedPnl)}</span></>} />
        </> : !summary.error && Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-[118px]" />)}
      </div>

      <div className="mt-6 grid gap-6 xl:grid-cols-3">
        {/* /portfolio/performance is rebuilt from the ledger, so it ends at the last ledger event,
            not at the current tick; label it so it is not confused with Total P&L above. */}
        <Card className="xl:col-span-2" title="Performance" action={performance.data && (
          <span className="text-right text-sm">
            <span className={`font-medium tabular-nums ${trendClass(performance.data.change)}`}>
              {signedMoney(performance.data.change)} ({signedPercent(performance.data.changeRatio)})
            </span>
            <span className="block text-xs text-slate-400">through {dateTime(performance.data.to)}</span>
          </span>)}>
          {performance.error ? <ErrorState error={performance.error} onRetry={performance.reload} />
            : !performance.data ? <Skeleton className="h-[260px]" />
              : performance.data.points.length < 2 ? <EmptyState title="Not enough history yet">Performance is rebuilt from your ledger; it appears after your first trades.</EmptyState>
                : <>
                  <PerformanceChart points={performance.data.points} />
                  <div className="mt-3 flex flex-wrap gap-x-6 gap-y-1 text-xs text-slate-500">
                    <span>Peak {money(performance.data.peakValue)}</span>
                    <span>Max drawdown {percent(performance.data.maxDrawdownRatio)}</span>
                    <span>{performance.data.pointCount} points · {dateTime(performance.data.from)} – {dateTime(performance.data.to)}</span>
                  </div>
                </>}
        </Card>

        <Card title="Allocation">
          {risk.error ? <ErrorState error={risk.error} onRetry={risk.reload} compact />
            : !risk.data ? <Skeleton className="h-[220px]" />
              : <>
                <AllocationChart slices={risk.data.byAssetClass} height={160} />
                {risk.data.warnings.length > 0 && (
                  <ul className="mt-4 space-y-1.5">
                    {risk.data.warnings.map((w) => (
                      <li key={w} className="flex gap-2 text-xs text-amber-700"><TriangleAlert className="size-3.5 shrink-0" />{w}</li>
                    ))}
                  </ul>
                )}
                <Link to="/portfolio" className="mt-4 inline-block text-sm font-medium text-sky-700 hover:underline">Risk details</Link>
              </>}
        </Card>
      </div>

      <div className="mt-6 grid gap-6 xl:grid-cols-3">
        <Card className="xl:col-span-2" title="Recent orders" bodyClassName="p-0"
          action={<Link to="/orders" className="text-sm font-medium text-sky-700 hover:underline">
            All orders{open.data && open.data.totalElements > 0 ? ` · ${open.data.totalElements} open` : ''}
          </Link>}>
          {recent.error ? <ErrorState error={recent.error} onRetry={recent.reload} />
            : !recent.data ? <Spinner />
              : recent.data.content.length === 0 ? <EmptyState title="No orders yet"><Link to="/market" className="text-sky-700 hover:underline">Browse the market</Link> to place your first trade.</EmptyState>
                : <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead className="bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500">
                      <tr><th className="px-5 py-2.5 font-medium">Symbol</th><th className="px-3 py-2.5 font-medium">Side</th><th className="px-3 py-2.5 text-right font-medium">Qty</th><th className="px-3 py-2.5 font-medium">Status</th><th className="px-5 py-2.5 text-right font-medium">Placed</th></tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {recent.data.content.map((o) => (
                        <tr key={o.id} className="hover:bg-slate-50">
                          <td className="px-5 py-2.5"><Link to={`/orders/${o.id}`} className="font-semibold hover:text-sky-700">{o.symbol}</Link> <span className="text-xs text-slate-400">{o.type}</span></td>
                          <td className="px-3 py-2.5"><SideBadge side={o.side} /></td>
                          <td className="px-3 py-2.5 text-right">{quantity(o.quantity)}</td>
                          <td className="px-3 py-2.5"><OrderStatusBadge status={o.status} /></td>
                          <td className="px-5 py-2.5 text-right text-slate-500">{dateTime(o.createdAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>}
        </Card>

        <Card title="Watchlist" bodyClassName="p-0" action={<Link to="/watchlist" className="text-sm font-medium text-sky-700 hover:underline">Manage</Link>}>
          {watchlist.error ? <ErrorState error={watchlist.error} onRetry={watchlist.reload} />
            : !watchlist.data ? <Spinner />
              : watchlist.data.length === 0 ? <EmptyState title="Your watchlist is empty">Star instruments in <Link to="/market" className="text-sky-700 hover:underline">Market watch</Link>.</EmptyState>
                : <ul className="divide-y divide-slate-100">
                  {watchlist.data.slice(0, 6).map((q) => (
                    <li key={q.symbol} className="flex items-center justify-between gap-3 px-5 py-3">
                      <SymbolLink symbol={q.symbol} name={q.name} />
                      <div className="text-right text-sm">
                        <p className="font-medium tabular-nums">{money(q.price)}</p>
                        <Change ratio={q.changeRatio} className="text-xs" />
                      </div>
                    </li>
                  ))}
                </ul>}
        </Card>
      </div>
      <SimulatedNote className="mt-6" />
    </>
  );
}
