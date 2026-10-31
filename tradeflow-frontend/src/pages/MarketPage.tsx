import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router';
import { RefreshCw, Search, Star } from 'lucide-react';
import { get } from '../api/client';
import { marketApi, watchlistApi } from '../api/endpoints';
import type { HistoryResponse } from '../api/types';
import { Sparkline } from '../components/charts';
import { Change, SimulatedNote, SymbolLink } from '../components/trading';
import { Button, Card, cx, EmptyState, ErrorState, PageHeader, Spinner, useToast } from '../components/ui';
import { money, time } from '../lib/format';
import { invalidate, useApi } from '../lib/useApi';

export function MarketPage() {
  const toast = useToast();
  const navigate = useNavigate();
  const quotes = useApi(() => marketApi.quotes(), [], { topics: ['market'], intervalMs: 15_000 });
  const watchlist = useApi(() => watchlistApi.list(), [], { topics: ['watchlist'] });
  const symbols = quotes.data?.map((q) => q.symbol).join(',') ?? '';
  // GET /api/demo/market/history?symbols=A,B&limit=N (MarketDataController.histories)
  const history = useApi(
    () => get<Record<string, HistoryResponse>>('/demo/market/history', { params: { symbols, limit: 40 } }),
    [symbols], { enabled: symbols !== '', topics: ['market'], intervalMs: 30_000 },
  );
  const [query, setQuery] = useState('');
  const [sector, setSector] = useState('');
  const [busy, setBusy] = useState<string | null>(null);

  const watched = useMemo(() => new Set(watchlist.data?.map((q) => q.symbol)), [watchlist.data]);
  const sectors = useMemo(() => [...new Set(quotes.data?.map((q) => q.sector))].sort(), [quotes.data]);
  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    return (quotes.data ?? []).filter((r) => (!sector || r.sector === sector)
      && (!q || r.symbol.toLowerCase().includes(q) || r.name.toLowerCase().includes(q)));
  }, [quotes.data, query, sector]);

  async function toggleWatch(symbol: string) {
    setBusy(symbol);
    try {
      if (watched.has(symbol)) await watchlistApi.remove(symbol);
      else await watchlistApi.add(symbol);
      invalidate('watchlist');
    } catch (e) {
      toast('error', (e as Error).message);
    } finally {
      setBusy(null);
    }
  }

  const asOf = quotes.data?.[0]?.asOf;

  return (
    <>
      <PageHeader title="Market watch"
        subtitle={asOf ? <>{quotes.data?.length} instruments · simulated prices as of {time(asOf)} (tick {quotes.data?.[0]?.tick})</> : 'Simulated NSE-style instruments'}
        actions={<Button variant="secondary" size="sm" onClick={() => invalidate('market')} loading={quotes.refreshing}><RefreshCw className="size-3.5" /> Refresh</Button>}
      />
      <Card bodyClassName="p-0">
        <div className="flex flex-col gap-3 border-b border-slate-100 p-4 sm:flex-row">
          <label className="relative flex-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 size-4 -translate-y-1/2 text-slate-400" />
            <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search symbol or company"
              className="w-full rounded-lg border border-slate-300 py-2 pl-9 pr-3 text-sm focus:border-slate-500 focus:outline-none focus:ring-2 focus:ring-slate-200" />
          </label>
          <select value={sector} onChange={(e) => setSector(e.target.value)} aria-label="Sector"
            className="rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none">
            <option value="">All sectors</option>
            {sectors.map((s) => <option key={s}>{s}</option>)}
          </select>
        </div>
        {quotes.error ? <ErrorState error={quotes.error} onRetry={quotes.reload} />
          : !quotes.data ? <Spinner />
            : rows.length === 0 ? <EmptyState title="No instruments match your filters" />
              : <div className="overflow-x-auto">
                <table className="w-full text-sm" data-testid="market-table">
                  <thead className="bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500">
                    <tr>
                      <th className="w-10 px-4 py-2.5"><span className="sr-only">Watch</span></th>
                      <th className="px-3 py-2.5 font-medium">Instrument</th>
                      <th className="hidden px-3 py-2.5 font-medium md:table-cell">Sector</th>
                      <th className="px-3 py-2.5 text-right font-medium">Price</th>
                      <th className="px-3 py-2.5 text-right font-medium">Change</th>
                      <th className="hidden px-5 py-2.5 font-medium sm:table-cell">Trend</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {rows.map((q) => (
                      <tr key={q.symbol} className="cursor-pointer hover:bg-slate-50" onClick={() => navigate(`/demo/market/${q.symbol}`)}>
                        <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                          <button onClick={() => toggleWatch(q.symbol)} disabled={busy === q.symbol}
                            aria-label={watched.has(q.symbol) ? `Remove ${q.symbol} from watchlist` : `Add ${q.symbol} to watchlist`}
                            className="rounded p-1 hover:bg-slate-100 disabled:opacity-40">
                            <Star className={cx('size-4', watched.has(q.symbol) ? 'fill-amber-400 text-amber-400' : 'text-slate-300')} />
                          </button>
                        </td>
                        <td className="px-3 py-3"><SymbolLink symbol={q.symbol} name={q.name} /></td>
                        <td className="hidden px-3 py-3 text-slate-500 md:table-cell">
                          {q.sector} <span className="ml-1 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] font-medium text-slate-500">{q.assetClass}</span>
                        </td>
                        <td className="px-3 py-3 text-right font-medium">{money(q.price)}</td>
                        <td className="px-3 py-3 text-right"><Change change={q.change} ratio={q.changeRatio} className="justify-end text-xs sm:text-sm" /></td>
                        <td className="hidden px-5 py-3 sm:table-cell">
                          <Sparkline values={history.data?.[q.symbol]?.points.map((p) => p.price) ?? []} />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>}
      </Card>
      <SimulatedNote className="mt-4" />
    </>
  );
}
