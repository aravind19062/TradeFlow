import { Link } from 'react-router';
import { ArrowRight, Banknote, Eye, GraduationCap, ListOrdered, LoaderCircle, PieChart, PiggyBank, TrendingUp, TriangleAlert, Wallet } from 'lucide-react';
import { orderApi, portfolioApi, watchlistApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { AllocationChart, PerformanceChart } from '../components/charts';
import { Change, OrderStatusBadge, SideBadge, SimulatedNote, SymbolLink } from '../components/trading';
import { Alert, Card, EmptyState, ErrorState, Flash, Skeleton, Spinner, Stat } from '../components/ui';
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
  const open = useApi(async () => {
    const [resting, partial] = await Promise.all([orderApi.list({ status: 'OPEN', size: 1 }), orderApi.list({ status: 'PARTIALLY_FILLED', size: 1 })]);
    return { totalElements: resting.totalElements + partial.totalElements };
  }, [], { topics: ['orders'], intervalMs: 15_000 });
  const watchlist = useApi(() => watchlistApi.list(), [], { topics: ['watchlist'], intervalMs: 30_000 });

  const s = summary.data;

  return (
    <>
      {/* Hero: a friendly greeting, the headline number and the two next steps. */}
      <section className="relative mb-6 overflow-hidden rounded-3xl bg-gradient-to-br from-indigo-600 via-violet-600 to-fuchsia-500 px-6 py-7 text-white shadow-lift sm:px-8">
        <div aria-hidden className="pointer-events-none absolute -right-10 -top-16 size-56 rounded-full bg-white/10" />
        <div aria-hidden className="pointer-events-none absolute -bottom-20 right-32 size-40 rounded-full bg-white/10" />
        <div aria-hidden className="pointer-events-none absolute right-8 top-8 hidden size-3 rounded-full bg-amber-300 sm:block" />
        <div className="relative flex flex-col gap-6 sm:flex-row sm:items-end sm:justify-between">
          <div>
            <p className="text-sm font-medium text-white/80">Demo account · virtual money</p>
            <h1 className="mt-1 text-3xl font-bold tracking-tight">Welcome{isNewUser ? '' : ' back'}, {user?.name?.split(' ')[0] ?? ''}</h1>
            <p className="mt-3 font-display text-4xl font-extrabold tabular-nums tracking-tight">
              {s ? <Flash value={s.totalValue}>{money(s.totalValue)}</Flash> : <span className="opacity-60">…</span>}
            </p>
            <p className="mt-1 text-sm text-white/80">
              {s ? <>{signedMoney(s.totalPnl)} ({signedPercent(s.totalReturnRatio)}) overall{s.pricesAsOf && <> · prices as of {time(s.pricesAsOf)}</>}</> : 'Your portfolio at a glance'}
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            <Link to="/demo/market" className="inline-flex items-center gap-1.5 rounded-xl bg-[#ffffff] px-4 py-2.5 text-sm font-semibold text-[#4338ca] shadow-sm transition active:scale-[0.97] hover:bg-[#eef2ff]">
              Trade <ArrowRight className="size-4" />
            </Link>
            <Link to="/academy" className="inline-flex items-center gap-1.5 rounded-xl bg-white/15 px-4 py-2.5 text-sm font-semibold text-white ring-1 ring-white/30 transition hover:bg-white/25 active:scale-[0.97]">
              <GraduationCap className="size-4" /> Keep learning
            </Link>
          </div>
        </div>
      </section>

      {summary.opening && (
        <div className="mb-6"><Alert tone="info" title="Opening your trading account">
          <span className="inline-flex items-center gap-2"><LoaderCircle className="size-3.5 animate-spin" /> portfolio-service is creating your cash account. This takes a moment after registration.</span>
        </Alert></div>
      )}
      {summary.error && <Card className="mb-6"><ErrorState error={summary.error} onRetry={summary.reload} /></Card>}
      {s && !s.pricesAvailable && (
        <div className="mb-6"><Alert tone="warning">Market data is unavailable right now; holdings are shown at cost.</Alert></div>
      )}

      <div className="stagger grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {s ? <>
          <Stat testId="stat-total-value" label="Portfolio value" value={money(s.totalValue)} flashValue={s.totalValue}
            tone="indigo" icon={<Wallet className="size-4" />} sub={<>Net deposits {money(s.netDeposits)}</>} />
          <Stat testId="stat-available-cash" label="Available cash" value={money(s.availableCash)} flashValue={s.availableCash}
            tone="teal" icon={<Banknote className="size-4" />}
            sub={s.reservedCash > 0 ? <>{money(s.reservedCash)} reserved for open orders</> : <>Cash balance {money(s.cashBalance)}</>} />
          <Stat testId="stat-total-pnl" label="Total P&L" value={signedMoney(s.totalPnl)} valueClass={trendClass(s.totalPnl)} flashValue={s.totalPnl}
            tone="emerald" icon={<TrendingUp className="size-4" />}
            sub={<span className={trendClass(s.totalReturnRatio)}>{signedPercent(s.totalReturnRatio)} return</span>} />
          <Stat testId="stat-unrealized" label="Unrealized / realized" value={<span className={trendClass(s.unrealizedPnl)}>{signedMoney(s.unrealizedPnl)}</span>}
            flashValue={s.unrealizedPnl} tone="amber" icon={<PiggyBank className="size-4" />}
            sub={<>Realized <span className={trendClass(s.realizedPnl)}>{signedMoney(s.realizedPnl)}</span></>} />
        </> : !summary.error && Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-[118px]" />)}
      </div>

      <div className="stagger mt-6 grid gap-6 xl:grid-cols-3">
        {/* /portfolio/performance is rebuilt from the ledger, so it ends at the last ledger event,
            not at the current tick; label it so it is not confused with Total P&L above. */}
        <Card className="xl:col-span-2" title="Performance" icon={<TrendingUp className="size-4" />} tone="indigo" action={performance.data && (
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

        <Card title="Allocation" icon={<PieChart className="size-4" />} tone="violet">
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
                <Link to="/demo/portfolio" className="mt-4 inline-block text-sm font-medium text-indigo-600 hover:underline">Risk details</Link>
              </>}
        </Card>
      </div>

      <div className="stagger mt-6 grid gap-6 xl:grid-cols-3">
        <Card className="xl:col-span-2" title="Recent orders" icon={<ListOrdered className="size-4" />} tone="sky" bodyClassName="p-0"
          action={<Link to="/demo/orders" className="text-sm font-medium text-indigo-600 hover:underline">
            All orders{open.data && open.data.totalElements > 0 ? ` · ${open.data.totalElements} open` : ''}
          </Link>}>
          {recent.error ? <ErrorState error={recent.error} onRetry={recent.reload} />
            : !recent.data ? <Spinner />
              : recent.data.content.length === 0 ? <EmptyState title="No orders yet" tone="sky"><Link to="/demo/market" className="text-indigo-600 hover:underline">Browse the market</Link> to place your first trade.</EmptyState>
                : <div className="overflow-x-auto">
                  <table className="w-full text-sm">
                    <thead className="bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500">
                      <tr><th className="px-5 py-2.5 font-medium">Symbol</th><th className="px-3 py-2.5 font-medium">Side</th><th className="px-3 py-2.5 text-right font-medium">Qty</th><th className="px-3 py-2.5 font-medium">Status</th><th className="px-5 py-2.5 text-right font-medium">Placed</th></tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {recent.data.content.map((o) => (
                        <tr key={o.id} className="transition-colors hover:bg-slate-50">
                          <td className="px-5 py-2.5"><Link to={`/demo/orders/${o.id}`} className="font-semibold transition-colors hover:text-indigo-600">{o.symbol}</Link> <span className="text-xs text-slate-400">{o.type}</span></td>
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

        <Card title="Watchlist" icon={<Eye className="size-4" />} tone="amber" bodyClassName="p-0" action={<Link to="/demo/watchlist" className="text-sm font-medium text-indigo-600 hover:underline">Manage</Link>}>
          {watchlist.error ? <ErrorState error={watchlist.error} onRetry={watchlist.reload} />
            : !watchlist.data ? <Spinner />
              : watchlist.data.length === 0 ? <EmptyState title="Your watchlist is empty" tone="amber">Star instruments in <Link to="/demo/market" className="text-indigo-600 hover:underline">Market watch</Link>.</EmptyState>
                : <ul className="divide-y divide-slate-100">
                  {watchlist.data.slice(0, 6).map((q) => (
                    <li key={q.symbol} className="flex items-center justify-between gap-3 px-5 py-3 transition-colors hover:bg-slate-50/80">
                      <SymbolLink symbol={q.symbol} name={q.name} />
                      <div className="text-right text-sm">
                        <p className="font-medium tabular-nums"><Flash value={q.price}>{money(q.price)}</Flash></p>
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
