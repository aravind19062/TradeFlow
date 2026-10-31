import { useState } from 'react';
import { ApiError } from '../api/client';
import { academyApi, reviewApi } from '../api/endpoints';
import type { ReviewCase } from '../api/types';
import { Alert, Button, Card, EmptyState, ErrorState, Field, PageHeader, Segmented, Spinner, useToast } from '../components/ui';
import { dateTime } from '../lib/format';
import { invalidate, useApi } from '../lib/useApi';

/** Readiness review queue for authorised reviewers (READINESS_REVIEWER or ADMIN). Decisions are audited. */
export function ReviewPage() {
  const queue = useApi(() => reviewApi.queue(), [], { topics: ['readiness'] });
  return (
    <>
      <PageHeader title="Readiness reviews" subtitle="Users whose final assessment failed the maximum number of times. You cannot review your own case." />
      {queue.error ? <Card><ErrorState error={queue.error} onRetry={queue.reload} /></Card>
        : !queue.data ? <Spinner />
          : queue.data.length === 0 ? <Card><EmptyState title="No cases waiting" /></Card>
            : <div className="space-y-4" data-testid="review-queue">{queue.data.map((c) => <CaseCard key={c.userId} c={c} />)}</div>}
    </>
  );
}

function CaseCard({ c }: { c: ReviewCase }) {
  const toast = useToast();
  const modules = useApi(() => academyApi.overview(), []);
  const [decision, setDecision] = useState<'ALLOW_RETAKE' | 'REQUIRE_REMEDIATION'>('ALLOW_RETAKE');
  const [notes, setNotes] = useState('');
  const [selected, setSelected] = useState<string[]>(c.weakModules);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function decide() {
    setBusy(true); setError(null);
    try {
      await reviewApi.decide(c.userId, decision, notes.trim(), decision === 'REQUIRE_REMEDIATION' ? selected : []);
      toast('success', `Decision recorded for user ${c.userId}`);
      invalidate('readiness');
    } catch (e) {
      setError(e instanceof ApiError ? (e.fieldErrors[0]?.message ? `${e.fieldErrors[0].field}: ${e.fieldErrors[0].message}` : e.message) : String(e));
    } finally { setBusy(false); }
  }
  return (
    <Card title={`User ${c.userId}`} action={<span className="text-xs text-slate-500">cycle since {dateTime(c.cycleStartedAt)}</span>}>
      <p className="text-sm text-slate-700">{c.failedAttempts} failed attempts · scores {c.scores.length ? c.scores.map((s) => `${s}%`).join(', ') : 'none submitted'}</p>
      {c.weakModules.length > 0 && <p className="mt-1 text-sm text-slate-500">Questions missed in: {c.weakModules.join(', ')}</p>}
      <div className="mt-4 space-y-3">
        <Segmented label="Decision" value={decision} onChange={setDecision} options={[
          { value: 'ALLOW_RETAKE', label: 'Allow new attempts' },
          { value: 'REQUIRE_REMEDIATION', label: 'Require modules first' },
        ]} />
        {decision === 'REQUIRE_REMEDIATION' && modules.data && (
          <fieldset className="grid gap-1 sm:grid-cols-2">
            <legend className="mb-1 text-sm font-medium text-slate-700">Modules to redo</legend>
            {modules.data.modules.map((m) => (
              <label key={m.slug} className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={selected.includes(m.slug)}
                  onChange={() => setSelected((s) => s.includes(m.slug) ? s.filter((x) => x !== m.slug) : [...s, m.slug])} />
                {m.title}
              </label>
            ))}
          </fieldset>
        )}
        <Field label="Notes (recorded in the audit log, min. 20 characters)">
          <textarea value={notes} onChange={(e) => setNotes(e.target.value)} rows={3} maxLength={1000}
            className="block w-full rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none" />
        </Field>
        {error && <Alert tone="error">{error}</Alert>}
        <Button onClick={decide} loading={busy} disabled={notes.trim().length < 20}>Record decision</Button>
      </div>
    </Card>
  );
}
