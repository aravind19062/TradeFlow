import { useEffect, useRef, useState, type FormEvent } from 'react';
import { Bot, ChevronDown, Info, Send, ShieldAlert, Sparkles, User } from 'lucide-react';
import { ApiError } from '../api/client';
import { assistantApi } from '../api/endpoints';
import type { AskResponse } from '../api/types';
import { Alert, Badge, Card, cx, PageHeader, Spinner } from '../components/ui';
import { useApi } from '../lib/useApi';

interface Turn {
  id: number;
  question: string;
  response?: AskResponse;
  error?: ApiError;
}

const MODE_STYLE = {
  LLM: { cls: 'bg-amber-50 text-amber-800 ring-1 ring-amber-200', label: 'AI-generated' },
  DETERMINISTIC: { cls: 'bg-slate-100 text-slate-700 ring-1 ring-slate-200', label: 'Calculated from your data' },
  REFUSED: { cls: 'bg-amber-50 text-amber-800 ring-1 ring-amber-200', label: 'Declined' },
} as const;

export function AssistantPage() {
  const caps = useApi(() => assistantApi.capabilities(), []);
  const [turns, setTurns] = useState<Turn[]>([]);
  const [question, setQuestion] = useState('');
  const [busy, setBusy] = useState(false);
  const bottom = useRef<HTMLDivElement>(null);

  useEffect(() => { bottom.current?.scrollIntoView({ behavior: 'smooth', block: 'end' }); }, [turns]);

  async function ask(q: string) {
    const text = q.trim();
    if (!text || busy) return;
    const id = Date.now();
    setTurns((t) => [...t, { id, question: text }]);
    setQuestion('');
    setBusy(true);
    try {
      const response = await assistantApi.ask(text);
      setTurns((t) => t.map((x) => (x.id === id ? { ...x, response } : x)));
    } catch (e) {
      const error = e instanceof ApiError ? e : new ApiError(0, 'CLIENT_ERROR', String(e));
      setTurns((t) => t.map((x) => (x.id === id ? { ...x, error } : x)));
    } finally {
      setBusy(false);
    }
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    void ask(question);
  }

  const examples = caps.data?.examples.map((e) => e.replace(/^•\s*/, '')).filter((e) => !e.includes('<')) ?? [];

  return (
    <>
      <PageHeader icon={<Bot className="size-5" />} tone="violet" title="AI portfolio assistant"
        subtitle="Ask about your own portfolio, risk, performance, watchlist, alerts or orders. Read-only: it cannot trade." />
      {caps.data && (
        <div className="mb-4 flex flex-wrap items-center gap-2 text-xs text-slate-500">
          <Badge className={caps.data.llmEnabled ? 'bg-indigo-50 text-indigo-700' : 'bg-slate-100 text-slate-600'}>
            {caps.data.llmEnabled ? <><Sparkles className="size-3" /> {caps.data.provider} · {caps.data.model}</> : 'No AI provider configured'}
          </Badge>
          {!caps.data.llmEnabled && <span>Answers are built deterministically from your TradeFlow data (TRADEFLOW_AI_PROVIDER=none).</span>}
        </div>
      )}

      <Card bodyClassName="p-0" className="flex min-h-[60vh] flex-col">
        <div className="flex-1 space-y-6 overflow-y-auto p-5" data-testid="assistant-thread">
          {turns.length === 0 && (
            <div className="py-6 text-center">
              <Bot className="mx-auto size-10 text-slate-300" />
              <p className="mt-3 text-sm font-medium text-slate-700">Try one of these</p>
              {caps.loading && <Spinner />}
              <div className="mx-auto mt-4 flex max-w-2xl flex-wrap justify-center gap-2">
                {examples.map((e) => (
                  <button key={e} onClick={() => ask(e)}
                    className="rounded-full border border-slate-200 bg-surface px-3 py-1.5 text-sm text-slate-700 hover:border-slate-300 hover:bg-slate-50">
                    {e}
                  </button>
                ))}
              </div>
            </div>
          )}
          {turns.map((t) => (
            <div key={t.id} className="space-y-3">
              <div className="flex justify-end gap-3">
                <div className="max-w-[85%] rounded-xl rounded-tr-sm bg-indigo-600 px-4 py-2.5 text-sm text-white">{t.question}</div>
                <div className="flex size-8 shrink-0 items-center justify-center rounded-full bg-slate-200"><User className="size-4 text-slate-600" /></div>
              </div>
              <div className="flex gap-3">
                <div className="flex size-8 shrink-0 items-center justify-center rounded-full bg-indigo-600"><Bot className="size-4 text-white" /></div>
                <div className="min-w-0 max-w-[85%] flex-1">
                  {!t.response && !t.error && <div className="flex items-center gap-2 py-2 text-sm text-slate-500"><Spinner label="Thinking…" /></div>}
                  {t.error && <Alert tone="error">{t.error.message}</Alert>}
                  {t.response && <Answer r={t.response} />}
                </div>
              </div>
            </div>
          ))}
          <div ref={bottom} />
        </div>
        <form onSubmit={onSubmit} className="flex gap-2 border-t border-slate-100 p-4">
          <input
            value={question} onChange={(e) => setQuestion(e.target.value)} maxLength={500} disabled={busy}
            placeholder="e.g. What are my realized and unrealized gains?" aria-label="Your question"
            className="flex-1 rounded-lg border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none focus:ring-2 focus:ring-slate-200"
          />
          <button type="submit" disabled={busy || !question.trim()} aria-label="Ask"
            className="inline-flex items-center gap-2 rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-50">
            <Send className="size-4" /> Ask
          </button>
        </form>
      </Card>
    </>
  );
}

function Answer({ r }: { r: AskResponse }) {
  const [showFacts, setShowFacts] = useState(false);
  const style = MODE_STYLE[r.mode];
  return (
    <div className="rounded-xl rounded-tl-sm border border-slate-200 bg-surface px-4 py-3" data-testid="assistant-answer" data-mode={r.mode}>
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <Badge className={style.cls}>{r.mode === 'REFUSED' && <ShieldAlert className="size-3" />}{style.label}</Badge>
        {r.mode === 'LLM' && r.model && <span className="text-xs text-slate-400">{r.provider} · {r.model}</span>}
      </div>
      <p className="whitespace-pre-line text-sm leading-relaxed text-slate-800">{r.answer}</p>
      {r.notice && <p className="mt-2 flex gap-1.5 text-xs text-slate-500"><Info className="mt-0.5 size-3.5 shrink-0" />{r.notice}</p>}
      {r.facts.length > 0 && (
        <div className="mt-3 border-t border-slate-100 pt-2">
          <button onClick={() => setShowFacts((s) => !s)} className="inline-flex items-center gap-1 text-xs font-medium text-slate-500 hover:text-slate-800">
            <ChevronDown className={cx('size-3.5 transition-transform', showFacts && 'rotate-180')} />
            Based on {r.facts.length} data source{r.facts.length > 1 ? 's' : ''}: {r.facts.map((f) => f.tool).join(', ')}
          </button>
          {showFacts && (
            <div className="mt-2 space-y-2">
              {r.facts.map((f, i) => (
                <details key={i} className="rounded-lg bg-slate-50 p-2 text-xs">
                  <summary className="cursor-pointer font-medium text-slate-600">{f.tool} <span className="text-slate-400">· {f.kind}</span></summary>
                  <pre className="mt-2 max-h-64 overflow-auto whitespace-pre-wrap break-all text-[11px] text-slate-600">{JSON.stringify(f.data, null, 2)}</pre>
                </details>
              ))}
            </div>
          )}
        </div>
      )}
      {r.disclaimers.length > 0 && <p className="mt-2 text-[11px] text-slate-400">{r.disclaimers.join(' ')}</p>}
    </div>
  );
}
