import { useEffect, useState, type FormEvent, type ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { CheckCircle2, ChevronLeft, Circle, ClipboardCheck, Clock, FileSignature, ShieldAlert, ShieldCheck, Target } from 'lucide-react';
import { ApiError } from '../api/client';
import { academyApi, readinessApi } from '../api/endpoints';
import type { ExerciseView, QuizAttemptView, QuizResultView, ReadinessState, SizingScenario } from '../api/types';
import { Alert, Badge, Button, Card, ConfirmDialog, cx, ErrorState, PageHeader, Spinner, TextInput, useToast } from '../components/ui';
import { dateTime, money } from '../lib/format';
import { invalidate, useApi } from '../lib/useApi';
import { AcademyDisclaimer, QuizForm, QuizResult } from './AcademyPages';

const STATE_LABEL: Record<ReadinessState, { label: string; cls: string }> = {
  NOT_STARTED: { label: 'Not started', cls: 'bg-slate-100 text-slate-700 ring-1 ring-slate-200' },
  IN_PROGRESS: { label: 'In progress', cls: 'bg-indigo-50 text-indigo-700 ring-1 ring-indigo-200' },
  ASSESSMENT_PENDING: { label: 'Assessment open', cls: 'bg-amber-50 text-amber-800 ring-1 ring-amber-200' },
  REVIEW_REQUIRED: { label: 'Under review', cls: 'bg-amber-50 text-amber-800 ring-1 ring-amber-200' },
  ACKNOWLEDGEMENT_PENDING: { label: 'Acknowledgement due', cls: 'bg-amber-50 text-amber-800 ring-1 ring-amber-200' },
  QUALIFIED: { label: 'Qualified', cls: 'bg-emerald-50 text-emerald-700 ring-1 ring-emerald-200' },
  ACCESS_REVOKED: { label: 'Access revoked', cls: 'bg-rose-50 text-rose-700 ring-1 ring-rose-200' },
};

const message = (e: unknown) => (e instanceof ApiError ? e.message : String(e));

export function ReadinessBadge({ state }: { state: ReadinessState }) {
  return <Badge className={STATE_LABEL[state].cls}>{STATE_LABEL[state].label}</Badge>;
}

function Step({ done, title, children }: { done: boolean; title: string; children?: ReactNode }) {
  return (
    <li className="flex gap-3">
      {done ? <CheckCircle2 className="mt-0.5 size-5 shrink-0 text-emerald-600" aria-label="Done" />
        : <Circle className="mt-0.5 size-5 shrink-0 text-slate-300" aria-label="Not done" />}
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-slate-900">{title}</p>
        {children && <div className="mt-1 text-sm text-slate-600">{children}</div>}
      </div>
    </li>
  );
}

export function ReadinessPage() {
  const status = useApi(() => readinessApi.status(), [], { topics: ['readiness', 'academy'] });
  const history = useApi(() => readinessApi.history(), [], { topics: ['readiness'] });
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [starting, setStarting] = useState(false);
  if (status.error) return <Card><ErrorState error={status.error} onRetry={status.reload} /></Card>;
  if (!status.data) return <Spinner />;
  const s = status.data;
  const c = s.checklist;
  const fin = c.finalAssessment;
  const cooling = fin.retakeAvailableAt != null && new Date(fin.retakeAvailableAt).getTime() > Date.now();

  async function startAssessment() {
    setStarting(true); setError(null);
    try {
      const attempt = await readinessApi.startAssessment();
      navigate('/readiness/assessment', { state: { attempt } });
    } catch (e) {
      setError(message(e));
      setStarting(false);
    }
  }

  return (
    <>
      <PageHeader icon={<ClipboardCheck className="size-5" />} tone="amber" title="Trading readiness" subtitle="What TradeFlow requires before you can request live-trading access."
        actions={<ReadinessBadge state={s.state} />} />
      <div className="mb-6" data-testid="readiness-next-step">
        <Alert tone={s.state === 'QUALIFIED' ? 'success' : s.state === 'ACCESS_REVOKED' ? 'error' : 'info'} title="Next step">
          {s.nextStep}
          {s.state === 'QUALIFIED' && s.expiresAt && <> Qualification valid until {dateTime(s.expiresAt)}.</>}
        </Alert>
      </div>
      {s.selfExcludedUntil && new Date(s.selfExcludedUntil).getTime() > Date.now() && (
        <div className="mb-6"><Alert tone="warning" title="Self-exclusion active">Live trading is paused until {dateTime(s.selfExcludedUntil)}.</Alert></div>
      )}
      {error && <div className="mb-6"><Alert tone="error">{error}</Alert></div>}

      <div className="grid gap-6 lg:grid-cols-3">
        <Card title="Requirements" className="lg:col-span-2">
          <ol className="space-y-5" data-testid="readiness-checklist">
            <Step done={c.modulesDone} title={`Complete all Academy modules (${c.modulesCompleted}/${c.moduleCount})`}>
              Read every lesson and pass each module quiz ({s.policy.modulePassMarkPct}%).{' '}
              <Link to="/academy" className="font-medium text-indigo-600 hover:underline">Go to the Academy</Link>
              {c.remediationModules.length > 0 && <p className="mt-1 text-amber-700">A reviewer asked you to redo: {c.remediationModules.join(', ')}.</p>}
            </Step>
            <Step done={c.exercisesDone} title="Complete the practical exercises in the demo">
              <ul className="mt-2 space-y-3">{c.exercises.map((e) => <ExerciseRow key={e.slug} exercise={e} onChange={() => invalidate('readiness')} />)}</ul>
            </Step>
            <Step done={fin.passed || ['ACKNOWLEDGEMENT_PENDING', 'QUALIFIED'].includes(s.state)}
              title={`Pass the final assessment (${fin.questions} questions, ${fin.passMarkPct}% to pass)`}>
              <p>{fin.timeLimitMinutes} minutes. After a failed attempt there is a {s.policy.finalRetakeCooldownHours}-hour pause;
                after {fin.maxFailedAttempts} failed attempts an authorised reviewer looks at your case.</p>
              {fin.lastScorePct != null && <p className="mt-1">Last score: {fin.lastScorePct}% · failed attempts: {fin.failedAttempts}/{fin.maxFailedAttempts}</p>}
              {cooling && <p className="mt-1 flex items-center gap-1 text-amber-700"><Clock className="size-3.5" /> Next attempt from {dateTime(fin.retakeAvailableAt)}</p>}
              {s.state === 'ASSESSMENT_PENDING' && (
                <Button className="mt-3" onClick={startAssessment} loading={starting} disabled={cooling}>Take the final assessment</Button>
              )}
            </Step>
            <Step done={s.state === 'QUALIFIED'} title="Sign the risk acknowledgement">
              {s.state === 'ACKNOWLEDGEMENT_PENDING'
                ? <Link to="/readiness/acknowledgement" className="inline-flex items-center gap-1 font-medium text-indigo-600 hover:underline"><FileSignature className="size-4" /> Read and sign</Link>
                : 'Available after passing the final assessment.'}
            </Step>
          </ol>
        </Card>
        <div className="space-y-6">
          <Card title={<span className="flex items-center gap-2">{s.eligibleForLiveTrading ? <ShieldCheck className="size-4 text-emerald-600" /> : <ShieldAlert className="size-4 text-slate-400" />} Live trading</span>}>
            <p className="text-sm text-slate-600" data-testid="readiness-eligibility">
              {s.eligibleForLiveTrading
                ? 'You meet TradeFlow\'s readiness requirements. Live trading also needs two-factor authentication and an authorised broker connection, and your broker applies its own checks.'
                : 'Not available yet. Readiness is checked by TradeFlow\'s servers on every live action, not by this page.'}
            </p>
          </Card>
          <SelfExclusionCard />
          <Card title="Policy" bodyClassName="text-sm text-slate-600">
            <p>Version {s.policy.version}: modules {s.policy.modulePassMarkPct}%, final {s.policy.finalPassMarkPct}%,
              {' '}{s.policy.finalRetakeCooldownHours} h retake pause, review after {s.policy.maxFailedFinalAttempts} failed attempts,
              {' '}valid {s.policy.qualificationValidityDays} days.</p>
            <p className="mt-2 text-xs text-slate-400">Demo profits, trade counts and trading volume are never used.</p>
          </Card>
        </div>
      </div>

      <Card title="History" className="mt-6" bodyClassName="p-0">
        {history.error ? <ErrorState error={history.error} onRetry={history.reload} compact />
          : !history.data ? <Spinner />
            : <ol className="divide-y divide-slate-100 text-sm" data-testid="readiness-history">
              {[...history.data].reverse().map((h) => (
                <li key={h.seq} className="flex flex-wrap items-center justify-between gap-2 px-5 py-2.5">
                  <span><span className="font-medium">{h.toState.replaceAll('_', ' ').toLowerCase()}</span>
                    <span className="text-slate-500"> · {h.reasonCode.replaceAll('_', ' ').toLowerCase()} · by {h.actorType.toLowerCase()}</span></span>
                  <span className="text-xs text-slate-500">{dateTime(h.occurredAt)}</span>
                </li>
              ))}
            </ol>}
      </Card>
      <div className="mt-8"><AcademyDisclaimer /></div>
    </>
  );
}

function ExerciseRow({ exercise: e, onChange }: { exercise: ExerciseView; onChange: () => void }) {
  const [busy, setBusy] = useState(false);
  const toast = useToast();
  const done = e.completedAt != null;
  async function start() {
    setBusy(true);
    try { await readinessApi.startExercise(e.slug); onChange(); } catch (err) { toast('error', message(err)); } finally { setBusy(false); }
  }
  return (
    <li className="rounded-lg border border-slate-200 p-3" data-testid={`exercise-${e.slug}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="flex items-center gap-2 font-medium text-slate-800">
          {done ? <CheckCircle2 className="size-4 text-emerald-600" /> : <Target className="size-4 text-slate-400" />}{e.title}
        </p>
        {done ? <Badge className="bg-emerald-50 text-emerald-700 ring-1 ring-emerald-200">Verified</Badge>
          : e.startedAt ? <Badge className="bg-indigo-50 text-indigo-700 ring-1 ring-indigo-200">Started</Badge>
            : e.slug !== 'position-sizing' && <Button size="sm" variant="secondary" onClick={start} loading={busy}>Start</Button>}
      </div>
      <p className="mt-1 text-xs text-slate-500">{e.description}</p>
      {e.startedAt && !done && e.slug !== 'position-sizing' && (
        <p className="mt-1 text-xs text-slate-500">Do it in <Link to="/demo/market" className="text-indigo-600 hover:underline">demo trading</Link>; it is verified automatically from your demo orders.</p>
      )}
      {e.slug === 'position-sizing' && !done && <SizingExercise />}
    </li>
  );
}

function SizingExercise() {
  const [scenario, setScenario] = useState<SizingScenario | null>(null);
  const [qty, setQty] = useState('');
  const [feedback, setFeedback] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  async function load() {
    setBusy(true);
    try { setScenario(await readinessApi.scenario()); invalidate('readiness'); } finally { setBusy(false); }
  }
  async function submit(ev: FormEvent) {
    ev.preventDefault();
    setBusy(true);
    try {
      const r = await readinessApi.answer(Number(qty));
      setScenario(r.scenario);
      setFeedback({ ok: r.correct, text: r.explanation });
    } catch (e) {
      setFeedback({ ok: false, text: message(e) });
    } finally { setBusy(false); }
  }
  if (!scenario) return <Button size="sm" variant="secondary" className="mt-2" onClick={load} loading={busy}>Get a scenario</Button>;
  return (
    <div className="mt-3 rounded-md bg-slate-50 p-3 text-sm" data-testid="sizing-scenario">
      <p>Account value <strong>{money(scenario.accountValue)}</strong>, risk <strong>{scenario.riskPct}%</strong> per trade,
        entry <strong>{money(scenario.entryPrice)}</strong>, stop <strong>{money(scenario.stopPrice)}</strong>.
        How many shares should you buy?</p>
      {scenario.answeredCorrectly
        ? <p className="mt-2 text-emerald-700">Correct: {scenario.requiredQuantity} shares. Now place a demo BUY of exactly {scenario.requiredQuantity}{' '}
          <Link to={`/demo/market/${scenario.symbol}`} className="font-medium underline">{scenario.symbol}</Link> by {dateTime(scenario.expiresAt)}.</p>
        : <form onSubmit={submit} className="mt-2 flex items-end gap-2">
          <div className="w-40"><TextInput label="Quantity" inputMode="numeric" value={qty} onChange={(e) => setQty(e.target.value.replace(/\D/g, ''))} /></div>
          <Button type="submit" size="sm" loading={busy} disabled={!qty}>Check</Button>
        </form>}
      {feedback && !scenario.answeredCorrectly && <p className={cx('mt-2', feedback.ok ? 'text-emerald-700' : 'text-rose-700')}>{feedback.text}</p>}
    </div>
  );
}

function SelfExclusionCard() {
  const toast = useToast();
  const [days, setDays] = useState('30');
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function confirm() {
    setBusy(true); setError(null);
    try {
      await readinessApi.selfExclude(Number(days));
      setConfirming(false);
      invalidate('readiness');
      toast('info', `Live trading paused for ${days} days`);
    } catch (e) { setError(message(e)); } finally { setBusy(false); }
  }
  return (
    <Card title="Take a break">
      <p className="text-sm text-slate-600">Pause your own live-trading access. The pause can be extended but not shortened.</p>
      <div className="mt-3 flex items-end gap-2">
        <div className="w-28"><TextInput label="Days (1–365)" inputMode="numeric" value={days} onChange={(e) => setDays(e.target.value.replace(/\D/g, ''))} /></div>
        <Button variant="secondary" onClick={() => setConfirming(true)} disabled={!days}>Pause</Button>
      </div>
      <ConfirmDialog open={confirming} title={`Pause live trading for ${days} days?`} confirmLabel="Pause live trading" variant="danger"
        busy={busy} error={error} onConfirm={confirm} onCancel={() => setConfirming(false)}>
        You will not be able to place live orders until the pause ends, and you cannot end it early. Demo trading and the Academy stay available.
      </ConfirmDialog>
    </Card>
  );
}

export function AssessmentPage() {
  const initial = (useLocation().state as { attempt?: QuizAttemptView } | null)?.attempt;
  const [attempt, setAttempt] = useState<QuizAttemptView | null>(initial ?? null);
  const [answers, setAnswers] = useState<Record<string, string[]>>({});
  const [result, setResult] = useState<QuizResultView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (attempt) return;
    readinessApi.startAssessment().then(setAttempt).catch((e) => setError(message(e)));
  }, [attempt]);
  async function submit() {
    if (!attempt) return;
    setBusy(true); setError(null);
    try {
      setResult(await academyApi.submit(attempt.id, attempt.questions.map((q) => ({ questionKey: q.key, optionKeys: answers[q.key] ?? [] }))));
      invalidate('readiness');
    } catch (e) { setError(message(e)); } finally { setBusy(false); }
  }
  if (result) return <QuizResult result={result} backTo="/readiness" />;
  if (!attempt) return error ? <Card><Alert tone="error">{error}</Alert><Link to="/readiness" className="mt-3 inline-block text-sm text-indigo-600">Back</Link></Card> : <Spinner />;
  const answered = attempt.questions.filter((q) => (answers[q.key] ?? []).length > 0).length;
  return <QuizForm attempt={attempt} answers={answers} setAnswers={setAnswers} answered={answered} busy={busy} error={error}
    onSubmit={submit} backTo="/readiness" title="Final readiness assessment" />;
}

export function AcknowledgementPage() {
  const text = useApi(() => readinessApi.acknowledgement(), []);
  const navigate = useNavigate();
  const [phrase, setPhrase] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (text.error) return <Card><ErrorState error={text.error} onRetry={text.reload} /></Card>;
  if (!text.data) return <Spinner />;
  const t = text.data;
  async function sign(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      await readinessApi.acknowledge(t.textVersion, phrase);
      invalidate('readiness');
      navigate('/readiness');
    } catch (err) { setError(message(err)); } finally { setBusy(false); }
  }
  return (
    <>
      <Link to="/readiness" className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-800"><ChevronLeft className="size-4" /> Readiness</Link>
      <article className="prose-lesson mx-auto max-w-3xl rounded-xl border border-slate-200 bg-surface px-6 py-8 shadow-sm sm:px-10" data-testid="acknowledgement">
        <Markdown remarkPlugins={[remarkGfm]}>{t.text}</Markdown>
        <form onSubmit={sign} className="not-prose mt-6 space-y-3 border-t border-slate-100 pt-6">
          <p className="text-sm">Type: <code className="rounded bg-slate-100 px-2 py-1 font-mono">{t.phrase}</code></p>
          <TextInput label="Confirmation phrase" value={phrase} onChange={(e) => setPhrase(e.target.value)} autoComplete="off" error={error ?? undefined} />
          <Button type="submit" loading={busy} disabled={phrase.trim() !== t.phrase}>Sign acknowledgement</Button>
          <p className="text-xs text-slate-400">Recorded with the text version ({t.textVersion}), its fingerprint, the time and your device.</p>
        </form>
      </article>
    </>
  );
}
