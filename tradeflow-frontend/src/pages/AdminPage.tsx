import { useState } from 'react';
import { FastForward, ShieldCheck } from 'lucide-react';
import { ApiError } from '../api/client';
import { adminApi, marketApi } from '../api/endpoints';
import type { ReconciliationReport } from '../api/types';
import { Alert, Button, Card, PageHeader, TextInput, useToast } from '../components/ui';
import { dateTime } from '../lib/format';
import { invalidate, useApi } from '../lib/useApi';

/** ADMIN tools that exist in the backend: advance the market simulation, reconcile all accounts. */
export function AdminPage() {
  const toast = useToast();
  const sim = useApi(() => marketApi.simulation(), [], { topics: ['market'] });
  const [ticks, setTicks] = useState('1');
  const [advancing, setAdvancing] = useState(false);
  const [report, setReport] = useState<ReconciliationReport | null>(null);
  const [reconciling, setReconciling] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function advance() {
    const n = Number(ticks);
    if (!Number.isInteger(n) || n < 1 || n > 100) { setError('Ticks must be a whole number between 1 and 100'); return; }
    setAdvancing(true);
    setError(null);
    try {
      const res = await marketApi.advance(n);
      // Resting limit orders may have filled and alerts triggered on the new ticks.
      invalidate('market', 'orders', 'portfolio', 'alerts', 'watchlist');
      toast('success', `Market advanced to tick ${res.currentTick}`);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setAdvancing(false);
    }
  }

  async function reconcile() {
    setReconciling(true);
    setError(null);
    try {
      setReport(await adminApi.reconcileAll());
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setReconciling(false);
    }
  }

  return (
    <>
      <PageHeader title="Admin" subtitle="Operator tools. Every call is authorised again by the gateway and the service (ROLE_ADMIN)." />
      {error && <div className="mb-4"><Alert tone="error">{error}</Alert></div>}
      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Market simulation">
          <p className="text-sm text-slate-600">{sim.data?.note}</p>
          <p className="mt-2 text-sm">Current tick: <span className="font-semibold tabular-nums" data-testid="admin-tick">{sim.data?.currentTick ?? '…'}</span></p>
          <div className="mt-4 flex items-end gap-3">
            <div className="w-28"><TextInput label="Ticks (1–100)" inputMode="numeric" value={ticks} onChange={(e) => setTicks(e.target.value)} /></div>
            <Button onClick={advance} loading={advancing}><FastForward className="size-4" /> Advance market</Button>
          </div>
        </Card>
        <Card title="Reconciliation (all accounts)">
          <Button variant="secondary" onClick={reconcile} loading={reconciling}><ShieldCheck className="size-4" /> Run reconciliation</Button>
          {report && (
            <div className="mt-4">
              <Alert tone={report.consistent ? 'success' : 'error'} title={report.consistent ? 'All accounts consistent' : `${report.issues.length} issue(s) found`}>
                {report.accountsChecked} accounts checked at {dateTime(report.checkedAt)}
                {report.issues.length > 0 && (
                  <ul className="mt-2 list-disc pl-4">{report.issues.map((i, k) => <li key={k}>User {i.userId} · {i.check}: expected {i.expected}, actual {i.actual}</li>)}</ul>
                )}
              </Alert>
            </div>
          )}
        </Card>
      </div>
    </>
  );
}
