import { useState } from 'react';
import { FastForward, ShieldCheck } from 'lucide-react';
import { ApiError } from '../api/client';
import { adminApi, adminLiveApi, adminReadinessApi, marketApi } from '../api/endpoints';
import type { AdminUserLive, AdminUserReadiness, ReconciliationReport } from '../api/types';
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
      <ReadinessAdmin />
      <LiveAdmin />
    </>
  );
}

/** Look up a user's readiness, verify their audit chain, revoke or reinstate (reason required, audited). */
function ReadinessAdmin() {
  const toast = useToast();
  const [userId, setUserId] = useState('');
  const [data, setData] = useState<AdminUserReadiness | null>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function load() {
    setBusy(true); setError(null);
    try { setData(await adminReadinessApi.user(Number(userId))); } catch (e) { setError(e instanceof ApiError ? e.message : String(e)); } finally { setBusy(false); }
  }
  async function act(kind: 'revoke' | 'reinstate') {
    setBusy(true); setError(null);
    try {
      await (kind === 'revoke' ? adminReadinessApi.revoke : adminReadinessApi.reinstate)(Number(userId), reason.trim());
      toast('success', kind === 'revoke' ? 'Readiness revoked' : 'Reinstated (the user must requalify)');
      setReason('');
      await load();
    } catch (e) { setError(e instanceof ApiError ? e.message : String(e)); setBusy(false); }
  }
  return (
    <Card title="Readiness (live-trading eligibility)" className="mt-6">
      <div className="flex items-end gap-3">
        <div className="w-40"><TextInput label="User id" inputMode="numeric" value={userId} onChange={(e) => setUserId(e.target.value.replace(/\D/g, ''))} /></div>
        <Button variant="secondary" onClick={load} loading={busy} disabled={!userId}>Look up</Button>
      </div>
      {error && <div className="mt-3"><Alert tone="error">{error}</Alert></div>}
      {data && (
        <div className="mt-4 space-y-3 text-sm" data-testid="admin-readiness">
          <p><strong>{data.status.state}</strong> ({data.status.reason}) · policy v{data.status.policyVersion} · eligible: {data.status.eligibleForLiveTrading ? 'yes' : 'no'}</p>
          <Alert tone={data.chain.intact ? 'success' : 'error'}>
            Audit chain {data.chain.intact ? `intact (${data.chain.events} events)` : `BROKEN at event ${data.chain.firstBrokenSeq}`}
          </Alert>
          <ol className="max-h-56 space-y-1 overflow-y-auto text-xs text-slate-600">
            {data.history.map((h) => <li key={h.seq}>#{h.seq} {h.fromState ?? '-'} → {h.toState} · {h.reasonCode} · {h.actorType} · {dateTime(h.occurredAt)}</li>)}
          </ol>
          <TextInput label="Reason (min. 10 characters, recorded)" value={reason} onChange={(e) => setReason(e.target.value)} />
          <div className="flex gap-2">
            <Button variant="danger" onClick={() => act('revoke')} loading={busy} disabled={reason.trim().length < 10}>Revoke</Button>
            <Button variant="secondary" onClick={() => act('reinstate')} loading={busy} disabled={reason.trim().length < 10 || data.status.state !== 'ACCESS_REVOKED'}>Reinstate</Button>
          </div>
        </div>
      )}
    </Card>
  );
}

/** Live-trading halts (kill switches) and a user's live audit trail. Halts never cancel existing orders. */
function LiveAdmin() {
  const toast = useToast();
  const global = useApi(() => adminLiveApi.killSwitch(), [], { topics: ['live'] });
  const [reason, setReason] = useState('');
  const [userId, setUserId] = useState('');
  const [data, setData] = useState<AdminUserLive | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const fail = (e: unknown) => setError(e instanceof ApiError ? e.message : String(e));
  async function load() {
    setBusy(true); setError(null);
    try { setData(await adminLiveApi.user(Number(userId))); } catch (e) { fail(e); } finally { setBusy(false); }
  }
  async function setGlobal(enabled: boolean) {
    setBusy(true); setError(null);
    try {
      await adminLiveApi.setKillSwitch(enabled, reason.trim());
      toast('success', enabled ? 'New live orders halted platform-wide' : 'Platform halt lifted');
      setReason(''); global.reload();
    } catch (e) { fail(e); } finally { setBusy(false); }
  }
  async function setUser(enabled: boolean) {
    setBusy(true); setError(null);
    try {
      await adminLiveApi.setUserKillSwitch(Number(userId), enabled, reason.trim());
      toast('success', enabled ? 'New live orders halted for this user' : 'User halt lifted');
      setReason(''); setBusy(false); await load();
    } catch (e) { fail(e); setBusy(false); }
  }
  const g = global.data;
  return (
    <Card title="Live trading controls" className="mt-6">
      <p className="text-sm text-slate-600">A halt blocks new live orders and broker links. It never cancels orders at the broker; users can still view and cancel them. Every change is audited.</p>
      <p className="mt-3 text-sm">Platform-wide: <strong data-testid="global-halt">{g ? (g.enabled ? 'HALTED' : 'open') : '…'}</strong>
        {g?.enabled && g.reason && <span className="text-slate-500"> ({g.reason})</span>}</p>
      <div className="mt-3 max-w-xl"><TextInput label="Reason (min. 10 characters, recorded)" value={reason} onChange={(e) => setReason(e.target.value)} /></div>
      <div className="mt-2 flex gap-2">
        <Button variant="danger" onClick={() => setGlobal(true)} loading={busy} disabled={reason.trim().length < 10 || !!g?.enabled}>Halt all live orders</Button>
        <Button variant="secondary" onClick={() => setGlobal(false)} loading={busy} disabled={reason.trim().length < 10 || !g?.enabled}>Lift platform halt</Button>
      </div>
      <div className="mt-6 flex items-end gap-3">
        <div className="w-40"><TextInput label="User id" inputMode="numeric" value={userId} onChange={(e) => setUserId(e.target.value.replace(/\D/g, ''))} /></div>
        <Button variant="secondary" onClick={load} loading={busy} disabled={!userId}>Look up</Button>
      </div>
      {error && <div className="mt-3"><Alert tone="error">{error}</Alert></div>}
      {data && (
        <div className="mt-4 space-y-3 text-sm" data-testid="admin-live">
          <p>User {data.userId}: <strong>{data.killSwitch.enabled ? 'halted' : 'not halted'}</strong> · {data.connections.length} broker link(s) · {data.orders.length} live order(s)</p>
          <div className="flex gap-2">
            <Button variant="danger" onClick={() => setUser(true)} loading={busy} disabled={reason.trim().length < 10 || data.killSwitch.enabled}>Halt this user</Button>
            <Button variant="secondary" onClick={() => setUser(false)} loading={busy} disabled={reason.trim().length < 10 || !data.killSwitch.enabled}>Lift halt</Button>
          </div>
          <Alert tone={data.chain.intact ? 'success' : 'error'}>
            Live audit chain {data.chain.intact ? `intact (${data.chain.events} events)` : `BROKEN at event ${data.chain.firstBrokenSeq}`}
          </Alert>
          <ol className="max-h-56 space-y-1 overflow-y-auto text-xs text-slate-600">
            {data.audit.map((a) => <li key={a.seq}>#{a.seq} {a.action} · {a.outcome}{a.detail && ` · ${a.detail}`} · actor {a.actorId} · {dateTime(a.occurredAt)}</li>)}
          </ol>
        </div>
      )}
    </Card>
  );
}
