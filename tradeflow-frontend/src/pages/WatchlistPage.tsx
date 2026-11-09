import { useState } from 'react';
import { Link } from 'react-router';
import { BellOff, Star, Trash2 } from 'lucide-react';
import { alertApi, marketApi, watchlistApi } from '../api/endpoints';
import type { AlertStatus } from '../api/types';
import { AlertForm } from '../components/AlertForm';
import { AlertStatusBadge, Change, SimulatedNote, SymbolLink } from '../components/trading';
import { Button, Card, ConfirmDialog, EmptyState, ErrorState, PageHeader, SelectInput, Spinner, Tabs, useToast } from '../components/ui';
import { dateTime, money } from '../lib/format';
import { invalidate, useApi } from '../lib/useApi';

export function WatchlistPage() {
  const toast = useToast();
  const watchlist = useApi(() => watchlistApi.list(), [], { topics: ['watchlist', 'market'], intervalMs: 30_000 });
  const instruments = useApi(() => marketApi.instruments(), []);
  const [alertTab, setAlertTab] = useState<AlertStatus | 'ALL'>('ACTIVE');
  const alerts = useApi(() => alertApi.list(alertTab === 'ALL' ? undefined : alertTab), [alertTab], { topics: ['alerts'], intervalMs: 30_000 });
  const [alertSymbol, setAlertSymbol] = useState('');
  const [removing, setRemoving] = useState<string | null>(null);
  const [cancelId, setCancelId] = useState<string | null>(null);
  const [cancelBusy, setCancelBusy] = useState(false);
  const [cancelError, setCancelError] = useState<string | null>(null);

  async function remove(symbol: string) {
    setRemoving(symbol);
    try {
      await watchlistApi.remove(symbol);
      invalidate('watchlist');
    } catch (e) {
      toast('error', (e as Error).message);
    } finally {
      setRemoving(null);
    }
  }

  async function cancelAlert() {
    if (!cancelId) return;
    setCancelBusy(true);
    setCancelError(null);
    try {
      await alertApi.cancel(cancelId);
      setCancelId(null);
      invalidate('alerts');
      toast('success', 'Alert cancelled');
    } catch (e) {
      setCancelError((e as Error).message);
    } finally {
      setCancelBusy(false);
    }
  }

  const selected = alertSymbol || watchlist.data?.[0]?.symbol || instruments.data?.[0]?.symbol || '';
  const selectedPrice = watchlist.data?.find((q) => q.symbol === selected)?.price;

  return (
    <>
      <PageHeader icon={<Star className="size-5" />} tone="amber" title="Watchlist & alerts" subtitle="Alerts are evaluated by the market-data service on every simulated price tick." />
      <div className="grid gap-6 lg:grid-cols-3">
        <Card className="lg:col-span-2" title="Watchlist" bodyClassName="p-0">
          {watchlist.error ? <ErrorState error={watchlist.error} onRetry={watchlist.reload} />
            : !watchlist.data ? <Spinner />
              : watchlist.data.length === 0
                ? <EmptyState title="Nothing on your watchlist" icon={<Star className="size-8" />}>Use the star in <Link to="/demo/market" className="text-indigo-600 hover:underline">Market watch</Link> to follow instruments.</EmptyState>
                : <table className="w-full text-sm" data-testid="watchlist-table">
                  <thead className="bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500">
                    <tr><th className="px-5 py-2.5 font-medium">Instrument</th><th className="px-3 py-2.5 text-right font-medium">Price</th><th className="px-3 py-2.5 text-right font-medium">Change</th><th className="px-5 py-2.5"><span className="sr-only">Remove</span></th></tr>
                  </thead>
                  <tbody className="divide-y divide-slate-100">
                    {watchlist.data.map((q) => (
                      <tr key={q.symbol} className="hover:bg-slate-50">
                        <td className="px-5 py-3"><SymbolLink symbol={q.symbol} name={q.name} /></td>
                        <td className="px-3 py-3 text-right font-medium">{money(q.price)}</td>
                        <td className="px-3 py-3 text-right"><Change change={q.change} ratio={q.changeRatio} className="justify-end" /></td>
                        <td className="px-5 py-3 text-right">
                          <Button variant="ghost" size="sm" onClick={() => remove(q.symbol)} loading={removing === q.symbol} aria-label={`Remove ${q.symbol}`}>
                            <Trash2 className="size-4" />
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>}
        </Card>

        <Card title="New price alert">
          {instruments.error ? <ErrorState error={instruments.error} onRetry={instruments.reload} compact /> : (
            <div className="space-y-3">
              <SelectInput label="Instrument" value={selected} onChange={(e) => setAlertSymbol(e.target.value)}>
                {instruments.data?.map((i) => <option key={i.symbol} value={i.symbol}>{i.symbol} · {i.name}</option>)}
              </SelectInput>
              {selected && <AlertForm key={selected} symbol={selected} currentPrice={selectedPrice} />}
            </div>
          )}
        </Card>
      </div>

      <div className="mt-8">
        <Tabs value={alertTab} onChange={setAlertTab} tabs={[
          { value: 'ACTIVE', label: 'Active alerts' }, { value: 'TRIGGERED', label: 'Triggered' },
          { value: 'CANCELLED', label: 'Cancelled' }, { value: 'ALL', label: 'All' },
        ]} />
        <Card bodyClassName="p-0">
          {alerts.error ? <ErrorState error={alerts.error} onRetry={alerts.reload} />
            : !alerts.data ? <Spinner />
              : alerts.data.length === 0 ? <EmptyState title="No alerts here" icon={<BellOff className="size-8" />} />
                : <div className="overflow-x-auto">
                  <table className="w-full text-sm" data-testid="alerts-table">
                    <thead className="bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500">
                      <tr><th className="px-5 py-2.5 font-medium">Symbol</th><th className="px-3 py-2.5 font-medium">Condition</th><th className="px-3 py-2.5 font-medium">Status</th><th className="px-3 py-2.5 font-medium">Note</th><th className="px-3 py-2.5 font-medium">Created</th><th className="px-3 py-2.5 font-medium">Triggered</th><th className="px-5 py-2.5"><span className="sr-only">Actions</span></th></tr>
                    </thead>
                    <tbody className="divide-y divide-slate-100">
                      {alerts.data.map((a) => (
                        <tr key={a.id} className="hover:bg-slate-50">
                          <td className="px-5 py-3"><Link to={`/demo/market/${a.symbol}`} className="font-semibold hover:text-indigo-600">{a.symbol}</Link></td>
                          <td className="whitespace-nowrap px-3 py-3 tabular-nums">{a.direction === 'ABOVE' ? 'Rises to ≥' : 'Falls to ≤'} {money(a.threshold)}</td>
                          <td className="px-3 py-3"><AlertStatusBadge status={a.status} /></td>
                          <td className="max-w-48 truncate px-3 py-3 text-slate-500">{a.note ?? '—'}</td>
                          <td className="whitespace-nowrap px-3 py-3 text-slate-500">{dateTime(a.createdAt)}</td>
                          <td className="whitespace-nowrap px-3 py-3 text-slate-500">{a.triggeredAt ? <>{dateTime(a.triggeredAt)} at {money(a.triggeredPrice)}</> : '—'}</td>
                          <td className="px-5 py-3 text-right">
                            {a.status === 'ACTIVE' && <Button variant="secondary" size="sm" onClick={() => { setCancelError(null); setCancelId(a.id); }}>Cancel</Button>}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>}
        </Card>
      </div>
      <ConfirmDialog open={cancelId != null} title="Cancel this alert?" confirmLabel="Cancel alert" variant="danger"
        busy={cancelBusy} error={cancelError} onConfirm={cancelAlert} onCancel={() => setCancelId(null)}>
        The alert will stop being evaluated. This cannot be undone.
      </ConfirmDialog>
      <SimulatedNote className="mt-6" />
    </>
  );
}
