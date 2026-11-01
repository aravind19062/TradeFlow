import { useEffect, useMemo, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { BookOpen, CheckCircle2, ChevronLeft, ChevronRight, Circle, ClipboardCheck, Timer, XCircle } from 'lucide-react';
import { ApiError } from '../api/client';
import { academyApi } from '../api/endpoints';
import type { ModuleStatus, QuizAttemptView, QuizResultView } from '../api/types';
import { Alert, Badge, Button, Card, cx, ErrorState, PageHeader, Spinner, spotlight } from '../components/ui';
import { invalidate, useApi } from '../lib/useApi';

const STATUS: Record<ModuleStatus, { label: string; cls: string }> = {
  NOT_STARTED: { label: 'Not started', cls: 'bg-slate-100 text-slate-600 ring-1 ring-slate-200' },
  IN_PROGRESS: { label: 'In progress', cls: 'bg-sky-50 text-sky-700 ring-1 ring-sky-200' },
  QUIZ_AVAILABLE: { label: 'Quiz ready', cls: 'bg-amber-50 text-amber-800 ring-1 ring-amber-200' },
  COMPLETED: { label: 'Completed', cls: 'bg-emerald-50 text-emerald-700 ring-1 ring-emerald-200' },
};

export function AcademyDisclaimer() {
  return (
    <p className="text-xs text-slate-400">
      Educational content only, not investment advice. Completing the Academy or the readiness assessment shows
      knowledge of core concepts; it is not a judgement that trading is suitable for you or a guarantee of competence.
    </p>
  );
}

export function AcademyPage() {
  const overview = useApi(() => academyApi.overview(), [], { topics: ['academy'] });
  const o = overview.data;
  return (
    <>
      <PageHeader title="Trading Academy"
        subtitle={o ? `${o.modulesCompleted} of ${o.moduleCount} modules completed · module quizzes need ${o.modulePassMarkPct}%` : 'Learn the essentials before trading with real money.'} />
      {overview.error ? <Card><ErrorState error={overview.error} onRetry={overview.reload} /></Card>
        : !o ? <Spinner />
          : <>
            <div className="mb-6 h-2 overflow-hidden rounded-full bg-slate-200" role="progressbar" aria-valuenow={o.modulesCompleted} aria-valuemax={o.moduleCount}>
              <div className="h-full rounded-full bg-gradient-to-r from-emerald-400 to-cyan-500 transition-all duration-1000" style={{ width: `${(100 * o.modulesCompleted) / o.moduleCount}%` }} />
            </div>
            <div className="stagger grid gap-4 md:grid-cols-2 xl:grid-cols-3" data-testid="academy-modules">
              {o.modules.map((m) => (
                <Link key={m.slug} to={`/academy/${m.slug}`} onMouseMove={spotlight}
                  className="spotlight group flex flex-col overflow-hidden rounded-2xl border border-slate-200/80 bg-white/90 p-5 shadow-soft transition duration-300 hover:-translate-y-1 hover:border-emerald-200 hover:shadow-lift">
                  <div className="flex items-start justify-between gap-3">
                    <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-slate-800 to-slate-950 text-sm font-semibold text-white shadow-sm transition-all duration-300 group-hover:from-emerald-500 group-hover:to-cyan-600 group-hover:shadow-glow">{m.position}</span>
                    <Badge className={STATUS[m.status].cls}>{STATUS[m.status].label}</Badge>
                  </div>
                  <h2 className="mt-3 font-semibold text-slate-900 transition-colors group-hover:text-emerald-700">{m.title}</h2>
                  <p className="mt-1 flex-1 text-sm text-slate-500">{m.summary}</p>
                  <div className="mt-4 flex items-center justify-between text-xs text-slate-500">
                    <span><BookOpen className="mr-1 inline size-3.5" />{m.lessonsCompleted}/{m.lessonCount} lessons</span>
                    <span>{m.bestScorePct != null ? `Best quiz ${m.bestScorePct}%` : `Quiz: ${m.quizQuestions} questions`}</span>
                  </div>
                </Link>
              ))}
            </div>
          </>}
      <div className="mt-8"><AcademyDisclaimer /></div>
    </>
  );
}

export function ModulePage() {
  const { slug = '' } = useParams();
  const navigate = useNavigate();
  const mod = useApi(() => academyApi.module(slug), [slug], { topics: ['academy'] });
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (mod.error) return <Card><ErrorState error={mod.error} onRetry={mod.reload} /></Card>;
  if (!mod.data) return <Spinner />;
  const m = mod.data;
  const allRead = m.lessonsCompleted === m.lessonCount;

  async function startQuiz() {
    setStarting(true); setError(null);
    try {
      const attempt = await academyApi.startQuiz(slug);
      navigate(`/academy/${slug}/quiz`, { state: { attempt } });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
      setStarting(false);
    }
  }

  return (
    <>
      <Link to="/academy" className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-800"><ChevronLeft className="size-4" /> Academy</Link>
      <PageHeader title={`${m.position}. ${m.title}`} subtitle={m.summary} actions={<Badge className={STATUS[m.status].cls}>{STATUS[m.status].label}</Badge>} />
      <div className="grid gap-6 lg:grid-cols-3">
        <Card title="Lessons" className="lg:col-span-2" bodyClassName="p-0">
          <ol className="divide-y divide-slate-100">
            {m.lessons.map((l, i) => (
              <li key={l.slug}>
                <Link to={`/academy/${slug}/${l.slug}`} className="flex items-center gap-3 px-5 py-4 hover:bg-slate-50">
                  {l.completed ? <CheckCircle2 className="size-5 text-emerald-600" aria-label="Read" /> : <Circle className="size-5 text-slate-300" aria-label="Not read" />}
                  <span className="text-sm font-medium text-slate-800">{i + 1}. {l.title}</span>
                  <ChevronRight className="ml-auto size-4 text-slate-400" />
                </Link>
              </li>
            ))}
          </ol>
        </Card>
        <Card title={<span className="flex items-center gap-2"><ClipboardCheck className="size-4" /> Module quiz</span>}>
          <p className="text-sm text-slate-600">{m.quizQuestions} questions drawn from the module, {m.quizTimeLimitMinutes} minutes. Answers are checked on the server and explained afterwards.</p>
          {m.bestScorePct != null && (
            <p className="mt-3 text-sm">Best score: <strong className={m.quizPassed ? 'text-emerald-700' : 'text-rose-700'}>{m.bestScorePct}%</strong> {m.quizPassed ? '(passed)' : ''}</p>
          )}
          {error && <div className="mt-3"><Alert tone="error">{error}</Alert></div>}
          <Button className="mt-4 w-full" onClick={startQuiz} loading={starting} disabled={!allRead}>
            {m.quizPassed ? 'Practise again' : 'Start quiz'}
          </Button>
          {!allRead && <p className="mt-2 text-xs text-slate-500">Read every lesson to unlock the quiz.</p>}
        </Card>
      </div>
      <div className="mt-8"><AcademyDisclaimer /></div>
    </>
  );
}

export function LessonPage() {
  const { slug = '', lesson = '' } = useParams();
  const navigate = useNavigate();
  const view = useApi(() => academyApi.lesson(slug, lesson), [slug, lesson], { topics: ['academy'] });
  const [busy, setBusy] = useState(false);
  if (view.error) return <Card><ErrorState error={view.error} onRetry={view.reload} /></Card>;
  if (!view.data) return <Spinner />;
  const l = view.data;

  async function completeAndContinue() {
    setBusy(true);
    try {
      await academyApi.completeLesson(slug, lesson);
      invalidate('academy');
      navigate(l.nextLesson ? `/academy/${slug}/${l.nextLesson}` : `/academy/${slug}`);
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <Link to={`/academy/${slug}`} className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-800"><ChevronLeft className="size-4" /> {l.moduleTitle}</Link>
      <article className="prose-lesson mx-auto max-w-3xl rounded-xl border border-slate-200 bg-white px-6 py-8 shadow-sm sm:px-10" data-testid="lesson">
        <Markdown remarkPlugins={[remarkGfm]}>{l.body}</Markdown>
      </article>
      <div className="mx-auto mt-6 flex max-w-3xl items-center justify-between">
        {l.previousLesson ? <Link to={`/academy/${slug}/${l.previousLesson}`} className="text-sm text-slate-500 hover:text-slate-800">← Previous</Link> : <span />}
        <Button onClick={completeAndContinue} loading={busy}>
          {l.completed ? 'Continue' : 'Mark as read'}{l.nextLesson ? ' and continue' : ' and finish'}
        </Button>
      </div>
    </>
  );
}

export function QuizPage() {
  const { slug = '' } = useParams();
  const initial = (useLocation().state as { attempt?: QuizAttemptView } | null)?.attempt;
  const [attempt, setAttempt] = useState<QuizAttemptView | null>(initial ?? null);
  const [answers, setAnswers] = useState<Record<string, string[]>>({});
  const [result, setResult] = useState<QuizResultView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (attempt) return;
    academyApi.startQuiz(slug).then(setAttempt).catch((e) => setError(e instanceof ApiError ? e.message : String(e)));
  }, [slug, attempt]);

  async function submit() {
    if (!attempt) return;
    setBusy(true); setError(null);
    try {
      const r = await academyApi.submit(attempt.id, attempt.questions.map((q) => ({ questionKey: q.key, optionKeys: answers[q.key] ?? [] })));
      setResult(r);
      invalidate('academy');
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  if (result) return <QuizResult result={result} backTo={`/academy/${slug}`} />;
  if (error && !attempt) return <Card><Alert tone="error">{error}</Alert></Card>;
  if (!attempt) return <Spinner label="Preparing your quiz…" />;
  const answered = attempt.questions.filter((q) => (answers[q.key] ?? []).length > 0).length;

  return (
    <QuizForm attempt={attempt} answers={answers} setAnswers={setAnswers} answered={answered} busy={busy} error={error}
      onSubmit={submit} backTo={`/academy/${slug}`} />
  );
}

export function QuizForm({ attempt, answers, setAnswers, answered, busy, error, onSubmit, backTo, title }: {
  attempt: QuizAttemptView; answers: Record<string, string[]>; setAnswers: (f: (a: Record<string, string[]>) => Record<string, string[]>) => void;
  answered: number; busy: boolean; error: string | null; onSubmit: () => void; backTo: string; title?: string;
}) {
  const remaining = useCountdown(attempt.expiresAt);
  const toggle = (q: string, option: string, multi: boolean) => setAnswers((a) => {
    const current = a[q] ?? [];
    return { ...a, [q]: multi ? (current.includes(option) ? current.filter((o) => o !== option) : [...current, option]) : [option] };
  });
  return (
    <>
      <Link to={backTo} className="mb-4 inline-flex items-center gap-1 text-sm text-slate-500 hover:text-slate-800"><ChevronLeft className="size-4" /> Back</Link>
      <PageHeader title={title ?? 'Module quiz'} subtitle={`${attempt.questions.length} questions · pass mark ${attempt.passMarkPct}%`}
        actions={<span className={cx('inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium tabular-nums',
          remaining < 60_000 ? 'bg-rose-50 text-rose-700' : 'bg-slate-100 text-slate-700')}><Timer className="size-4" /> {formatMs(remaining)}</span>} />
      <ol className="space-y-4" data-testid="quiz">
        {attempt.questions.map((q, i) => (
          <li key={q.key}>
            <Card>
              <fieldset>
                <legend className="text-sm font-medium text-slate-900">{i + 1}. {q.prompt}</legend>
                <div className="mt-3 space-y-2">
                  {q.options.map((o) => {
                    const checked = (answers[q.key] ?? []).includes(o.key);
                    return (
                      <label key={o.key} className={cx('flex cursor-pointer items-start gap-3 rounded-lg border px-3 py-2.5 text-sm transition',
                        checked ? 'border-slate-900 bg-slate-50' : 'border-slate-200 hover:border-slate-300')}>
                        <input type={q.multi ? 'checkbox' : 'radio'} name={q.key} checked={checked} onChange={() => toggle(q.key, o.key, q.multi)} className="mt-0.5" />
                        <span>{o.text}</span>
                      </label>
                    );
                  })}
                </div>
              </fieldset>
            </Card>
          </li>
        ))}
      </ol>
      {error && <div className="mt-4"><Alert tone="error">{error}</Alert></div>}
      <div className="mt-6 flex items-center justify-between">
        <span className="text-sm text-slate-500">{answered} of {attempt.questions.length} answered</span>
        <Button onClick={onSubmit} loading={busy} disabled={remaining <= 0}>Submit answers</Button>
      </div>
    </>
  );
}

export function QuizResult({ result, backTo }: { result: QuizResultView; backTo: string }) {
  return (
    <>
      <PageHeader title={result.passed ? 'Passed' : 'Not passed yet'}
        subtitle={`${result.correctCount} of ${result.questionCount} correct · ${result.scorePct}% (pass mark ${result.passMarkPct}%)`}
        actions={<Link to={backTo} className="rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800">Done</Link>} />
      <div className="mb-6" data-testid="quiz-result">
        <Alert tone={result.passed ? 'success' : 'warning'}>
          {result.passed ? 'Well done. Review the explanations below. Knowledge is a start, not a guarantee.'
            : 'Review the explanations and the related lessons, then try again.'}
        </Alert>
      </div>
      <ol className="space-y-4">
        {result.questions.map((q, i) => (
          <li key={q.key}>
            <Card>
              <p className="flex items-start gap-2 text-sm font-medium text-slate-900">
                {q.correct ? <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-emerald-600" /> : <XCircle className="mt-0.5 size-4 shrink-0 text-rose-600" />}
                {i + 1}. {q.prompt}
              </p>
              <ul className="mt-3 space-y-1.5 text-sm">
                {q.options.map((o) => (
                  <li key={o.key} className={cx('rounded-md px-3 py-1.5', o.correct ? 'bg-emerald-50 text-emerald-900' : o.selected ? 'bg-rose-50 text-rose-900' : 'text-slate-600')}>
                    {o.correct ? '✓ ' : o.selected ? '✗ ' : ''}{o.text}{o.selected ? ' (your answer)' : ''}
                  </li>
                ))}
              </ul>
              <p className="mt-3 text-sm text-slate-600">{q.explanation}</p>
            </Card>
          </li>
        ))}
      </ol>
    </>
  );
}

function useCountdown(until: string): number {
  const end = useMemo(() => new Date(until).getTime(), [until]);
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(t);
  }, []);
  return Math.max(0, end - now);
}

function formatMs(ms: number): string {
  const s = Math.floor(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}
