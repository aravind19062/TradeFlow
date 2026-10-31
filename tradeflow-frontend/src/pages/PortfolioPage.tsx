import { useState } from 'react';
import { Link } from 'react-router';
import { ChevronLeft, ChevronRight, ShieldCheck, TriangleAlert } from 'lucide-react';
import { portfolioApi } from '../api/endpoints';
import type { LedgerType } from '../api/types';
import { AllocationChart, PerformanceChart } from '../components/charts';
import { Change, SimulatedNote, SymbolLink } from '../components/trading';
import { Alert, Badge, Button, Card, EmptyState, ErrorState, PageHeader, Segmented, Skeleton, Spinner, Stat, Tabs } from '../components/ui';
import { dateTime, money, number, percent, quantity, signedMoney, signedPercent, trendClass } from '../lib/format';
import { useApi } from '../lib/useApi';
import { usePortfolioSummary } from '../lib/usePortfolio';

type Tab = 'holdings' | 'performance' | 'risk' | 'transactions';
type AllocationView = 'bySector' | 'bySymbol' | 'byAssetClass';

export function PortfolioPage() {
  const [tab, setTab] = useState<Tab>('holdings');
  const summary = usePortfolioSummary();
  const ready = summary.data != null;
  const s = summary.data;

  return (
    <>
      <PageHeader title="Portfolio" subtitle="Holdings valued at the latest simulated prices; P&L derived from your ledger." />
      {summary.opening && <div className="mb-6"><Alert tone="info">Your trading account is being opened…</Alert></div>}
      {summary.error && <Card className="mb-6"><ErrorState error={summary.error} onRetry={summary.reload} /></Card>}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {s ? <>
          <Stat label="Total value" value={money(s.totalValue)} sub={<>Holdings {money(s.holdingsMarketValue)}</>} />
          <Stat label="Cash" value={money(s.cashBalance)} sub={<>{money(s.availableCash)} available · {money(s.reservedCash)} reserved</>} />
          <Stat label="Unrealized P&L" value={signedMoney(s.unrealizedPnl)} valueClass={trendClass(s.unrealizedPnl)} sub={<>Cost basis {money(s.holdingsCostBasis)}</>} />
          <Stat label="Total return" value={signedPercent(s.totalReturnRatio)} valueClass={trendClass(s.totalReturnRatio)}
            sub={<>Realized {signedMoney(s.realizedPnl)}</>} />
        </> : !summary.error && Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-[118px]" />)}
      </div>

      <div className="mt-8">
        <Tabs value={tab} onChange={setTab} tabs={[
          { value: 'holdings', label: 'Holdings', count: s?.holdings.length },
          { value: 'performance', label: 'Performance' },
          { value: 'risk', label: 'Risk & allocation' },
          { value: 'transactions', label: 'Transactions' },
        ]} />
        {tab === 'holdings' && <HoldingsTab summary={s} />}
        {tab === 'performance' && ready && <PerformanceTab />}
        {tab === 'risk' && ready && <RiskTab />}
        {tab === 'transactions' && ready && <TransactionsTab />}
      </div>
      <SimulatedNote className="mt-6" />
    </>
  );
}

function HoldingsTab({ summary }: { summary: ReturnType<typeof usePortfolioSummary>['data'] }) {
  if (!summary) return <Spinner />;
  if (summary.holdings.length === 0) {
    return <Card><EmptyState title="No holdings yet"><Link to="/demo/market" className="text-sky-700 hover:underline">Buy your first instrument</Link> from Market watch.</EmptyState></Card>;
  }
  return (
    <Card bodyClassName="p-0">
      <div className="overflow-x-auto">
        <table className="w-full text-sm" data-testid="holdings-table">
          <thead className="bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-5 py-2.5 font-medium">Instrument</th>
              <th className="px-3 py-2.5 text-right font-medium">Qty</th>
              <th className="px-3 py-2.5 text-right font-medium">Avg cost</th>
              <th className="px-3 py-2.5 text-right font-medium">Price</th>
              <th className="px-3 py-2.5 text-right font-medium">Market value</th>
              <th className="px-3 py-2.5 text-right font-medium">Unrealized</th>
              <th className="px-3 py-2.5 text-right font-medium">Realized</th>
              <th className="px-5 py-2.5 text-right font-medium">Weight</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {summary.holdings.map((h) => (
              <tr key={h.symbol} className="hover:bg-slate-50">
                <td className="px-5 py-3"><SymbolLink symbol={h.symbol} name={h.name} /></td>
                <td className="px-3 py-3 text-right">{quantity(h.quantity)}{h.reservedQuantity > 0 && <p className="text-xs text-amber-600">{quantity(h.reservedQuantity)} reserved</p>}</td>
                <td className="px-3 py-3 text-right">{money(h.averageCost)}</td>
                <td className="px-3 py-3 text-right">{money(h.marketPrice)}</td>
                <td className="px-3 py-3 text-right font-medium">{money(h.marketValue)}</td>
                <td className="px-3 py-3 text-right">
                  <span className={trendClass(h.unrealizedPnl)}>{signedMoney(h.unrealizedPnl)}</span>
                  <p className="text-xs"><Change ratio={h.unrealizedPnlRatio} /></p>
                </td>
                <td className={`px-3 py-3 text-right ${trendClass(h.realizedPnl)}`}>{signedMoney(h.realizedPnl)}</td>
                <td className="px-5 py-3 text-right text-slate-500">{percent(h.weight, 1)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  );
}

function PerformanceTab() {
  const perf = useApi(() => portfolioApi.performance(), [], { topics: ['portfolio'] });
  if (perf.error) return <Card><ErrorState error={perf.error} onRetry={perf.reload} /></Card>;
  if (!perf.data) return <Spinner />;
  const p = perf.data;
  return (
    <div className="space-y-6">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Stat label="Start value" value={money(p.startValue)} sub={dateTime(p.from)} />
        <Stat label="End value" value={money(p.endValue)} sub={dateTime(p.to)} />
        <Stat label="Change" value={signedMoney(p.change)} valueClass={trendClass(p.change)} sub={<span className={trendClass(p.changeRatio)}>{signedPercent(p.changeRatio)}</span>} />
        <Stat label="Max drawdown" value={percent(p.maxDrawdownRatio)} sub={<>Peak {money(p.peakValue)}</>} />
      </div>
      <Card title={`Portfolio value (${p.pointCount} points)`}>
        {p.points.length < 2 ? <EmptyState title="Not enough history yet" /> : <PerformanceChart points={p.points} height={320} />}
        <ul className="mt-4 space-y-1 text-xs text-slate-500">{p.notes.map((n) => <li key={n}>• {n}</li>)}</ul>
      </Card>
    </div>
  );
}

function RiskTab() {
  const risk = useApi(() => portfolioApi.risk(), [], { topics: ['portfolio'] });
  const recon = useApi(() => portfolioApi.reconciliation(), [], { topics: ['portfolio'] });
  const [view, setView] = useState<AllocationView>('bySector');
  if (risk.error) return <Card><ErrorState error={risk.error} onRetry={risk.reload} /></Card>;
  if (!risk.data) return <Spinner />;
  const r = risk.data;
  return (
    <div className="space-y-6">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Stat label="Invested" value={percent(r.investedWeight, 1)} sub={<>Cash {percent(r.cashWeight, 1)}</>} />
        <Stat label="Largest position" value={r.largestPosition ?? '—'} sub={r.largestPositionWeight != null ? `${percent(r.largestPositionWeight, 1)} of portfolio` : undefined} />
        <Stat label="Concentration (HHI)" value={number(r.herfindahlIndex)} sub={<>≈ {number(r.effectiveNumberOfHoldings)} effective holdings</>} />
        <Stat label="Max drawdown" value={percent(r.maxDrawdownRatio)} />
      </div>
      {r.warnings.length > 0 && (
        <Alert tone="warning" title="Risk warnings">
          <ul className="mt-1 space-y-0.5">{r.warnings.map((w) => <li key={w} className="flex gap-1.5"><TriangleAlert className="mt-0.5 size-3.5 shrink-0" />{w}</li>)}</ul>
        </Alert>
      )}
      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Allocation" action={<Segmented label="Allocation view" value={view} onChange={setView} options={[
          { value: 'bySector', label: 'Sector' }, { value: 'bySymbol', label: 'Symbol' }, { value: 'byAssetClass', label: 'Asset class' },
        ]} />}>
          {r[view].length === 0 ? <EmptyState title="Nothing invested yet" /> : <AllocationChart slices={r[view]} />}
        </Card>
        <Card title="Methodology">
          <ul className="space-y-2 text-sm text-slate-600">{r.methodology.map((m) => <li key={m}>• {m}</li>)}</ul>
        </Card>
      </div>
      <Card title="Ledger reconciliation">
        {recon.error ? <ErrorState error={recon.error} onRetry={recon.reload} compact />
          : !recon.data ? <Spinner />
            : recon.data.consistent
              ? <p className="flex items-center gap-2 text-sm text-emerald-700"><ShieldCheck className="size-4" /> Balances match the ledger and reservations (checked {dateTime(recon.data.checkedAt)}).</p>
              : <Alert tone="error" title="Inconsistencies found">
                <ul>{recon.data.issues.map((i, k) => <li key={k}>{i.check}: expected {i.expected}, actual {i.actual}</li>)}</ul>
              </Alert>}
      </Card>
    </div>
  );
}

const LEDGER_STYLE: Record<LedgerType, string> = {
  DEPOSIT: 'bg-sky-50 text-sky-700', BUY: 'bg-emerald-50 text-emerald-700', SELL: 'bg-rose-50 text-rose-700',
};

function TransactionsTab() {
  const [type, setType] = useState<LedgerType | ''>('');
  const [page, setPage] = useState(0);
  const tx = useApi(() => portfolioApi.transactions({ type: type || undefined, page, size: 20 }), [type, page], { topics: ['portfolio'] });
  return (
    <Card bodyClassName="p-0">
      <div className="flex items-center gap-2 border-b border-slate-100 p-4">
        <select aria-label="Transaction type" value={type} onChange={(e) => { setType(e.target.value as LedgerType | ''); setPage(0); }}
          className="rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm">
          <option value="">All types</option><option>DEPOSIT</option><option>BUY</option><option>SELL</option>
        </select>
      </div>
      {tx.error ? <ErrorState error={tx.error} onRetry={tx.reload} />
        : !tx.data ? <Spinner />
          : tx.data.content.length === 0 ? <EmptyState title="No transactions" />
            : <>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500">
                    <tr><th className="px-5 py-2.5 font-medium">Date</th><th className="px-3 py-2.5 font-medium">Type</th><th className="px-3 py-2.5 font-medium">Symbol</th><th className="px-3 py-2.5 text-right font-medium">Qty</th><th className="px-3 py-2.5 text-right font-medium">Price</th><th className="px-3 py-2.5 text-right font-medium">Cash Δ</th><th className="px-3 py-2.5 text-right font-medium">Balance</th><th className="px-5 py-2.5 text-right font-medium">Realized</th></tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {tx.data.content.map((e) => (
                      <tr key={e.id} className="hover:bg-slate-50">
                        <td className="whitespace-nowrap px-5 py-3 text-slate-500">{dateTime(e.occurredAt)}</td>
                        <td className="px-3 py-3"><Badge className={LEDGER_STYLE[e.type]}>{e.type}</Badge></td>
                        <td className="px-3 py-3 font-medium">{e.symbol ?? '—'}</td>
                        <td className="px-3 py-3 text-right">{quantity(e.quantity)}</td>
                        <td className="px-3 py-3 text-right">{money(e.price)}</td>
                        <td className={`px-3 py-3 text-right ${trendClass(e.cashDelta)}`}>{signedMoney(e.cashDelta)}</td>
                        <td className="px-3 py-3 text-right">{money(e.cashBalanceAfter)}</td>
                        <td className={`px-5 py-3 text-right ${trendClass(e.realizedPnl)}`}>{e.realizedPnl != null ? signedMoney(e.realizedPnl) : '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {tx.data.totalPages > 1 && (
                <div className="flex items-center justify-between border-t border-slate-100 px-5 py-3 text-sm text-slate-500">
                  <span>Page {tx.data.page + 1} of {tx.data.totalPages}</span>
                  <div className="flex gap-2">
                    <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)} aria-label="Previous page"><ChevronLeft className="size-4" /></Button>
                    <Button variant="secondary" size="sm" disabled={page + 1 >= tx.data.totalPages} onClick={() => setPage(page + 1)} aria-label="Next page"><ChevronRight className="size-4" /></Button>
                  </div>
                </div>
              )}
            </>}
    </Card>
  );
}
