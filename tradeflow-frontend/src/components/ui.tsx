import {
  createContext, useCallback, useContext, useEffect, useId, useRef, useState,
  type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes,
} from 'react';
import { createPortal } from 'react-dom';
import { CircleCheck, Inbox, Info, LoaderCircle, RefreshCw, TriangleAlert, X } from 'lucide-react';
import type { ApiError } from '../api/client';

export function cx(...classes: (string | false | null | undefined)[]): string {
  return classes.filter(Boolean).join(' ');
}

// ---------------------------------------------------------------- motion
/**
 * Briefly highlights its content when `value` changes: green when a number rises, red when it falls,
 * blue for any other change. Nothing happens on first render.
 */
export function Flash({ value, children, className }: { value: unknown; children: ReactNode; className?: string }) {
  const previous = useRef(value);
  const [flash, setFlash] = useState<{ n: number; cls: string }>({ n: 0, cls: '' });
  useEffect(() => {
    const before = previous.current;
    previous.current = value;
    if (before == null || value == null || Object.is(before, value)) return;
    const cls = typeof before === 'number' && typeof value === 'number'
      ? (value > before ? 'animate-flash-up' : 'animate-flash-down') : 'animate-flash';
    setFlash((f) => ({ n: f.n + 1, cls }));
  }, [value]);
  return <span key={flash.n} className={cx('-mx-1 rounded-md px-1', flash.cls, className)}>{children}</span>;
}

// ---------------------------------------------------------------- layout
export function PageHeader({ title, subtitle, actions, icon, tone = 'indigo' }: {
  title: string; subtitle?: ReactNode; actions?: ReactNode; icon?: ReactNode; tone?: Tone;
}) {
  return (
    <div className="mb-7 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div className="flex items-center gap-3.5">
        {icon && <span className={cx('flex size-11 shrink-0 items-center justify-center rounded-2xl', TONES[tone].chip)} aria-hidden>{icon}</span>}
        <div>
          <h1 className="text-[26px] font-bold tracking-tight text-slate-900">{title}</h1>
          {subtitle && <p className="mt-0.5 text-sm text-slate-500">{subtitle}</p>}
        </div>
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

/** Soft colour families used for icon chips, stat cards and empty states. Literal classes so Tailwind keeps them. */
export type Tone = 'indigo' | 'violet' | 'sky' | 'teal' | 'emerald' | 'amber' | 'rose';
export const TONES: Record<Tone, { chip: string; tint: string; text: string }> = {
  indigo: { chip: 'bg-indigo-100 text-indigo-700', tint: 'from-indigo-50', text: 'text-indigo-700' },
  violet: { chip: 'bg-violet-100 text-violet-700', tint: 'from-violet-50', text: 'text-violet-700' },
  sky: { chip: 'bg-sky-100 text-sky-700', tint: 'from-sky-50', text: 'text-sky-700' },
  teal: { chip: 'bg-teal-100 text-teal-700', tint: 'from-teal-50', text: 'text-teal-700' },
  emerald: { chip: 'bg-emerald-100 text-emerald-700', tint: 'from-emerald-50', text: 'text-emerald-700' },
  amber: { chip: 'bg-amber-100 text-amber-700', tint: 'from-amber-50', text: 'text-amber-700' },
  rose: { chip: 'bg-rose-100 text-rose-700', tint: 'from-rose-50', text: 'text-rose-700' },
};

const surface = 'rounded-2xl border border-slate-200/80 bg-surface shadow-soft';

export function Card({ title, action, children, className, bodyClassName, icon, tone = 'indigo' }: {
  title?: ReactNode; action?: ReactNode; children: ReactNode; className?: string; bodyClassName?: string;
  icon?: ReactNode; tone?: Tone;
}) {
  return (
    <section className={cx(surface, className)}>
      {(title || action) && (
        <header className="flex items-center justify-between gap-3 px-5 pb-1 pt-4">
          <h2 className="flex items-center gap-2.5 text-[15px] font-semibold text-slate-900">
            {icon && <span className={cx('flex size-7 items-center justify-center rounded-lg', TONES[tone].chip)} aria-hidden>{icon}</span>}
            {title}
          </h2>
          {action}
        </header>
      )}
      <div className={cx('p-5', bodyClassName)}>{children}</div>
    </section>
  );
}

export function Stat({ label, value, sub, valueClass, testId, icon, flashValue, tone = 'indigo' }: {
  label: string; value: ReactNode; sub?: ReactNode; valueClass?: string; testId?: string;
  icon?: ReactNode; flashValue?: number; tone?: Tone;
}) {
  return (
    <div data-testid={testId}
      className={cx(surface, 'group bg-gradient-to-br to-transparent to-60% p-5 transition duration-200 hover:-translate-y-0.5 hover:shadow-lift', TONES[tone].tint)}>
      <div className="flex items-center justify-between gap-3">
        <p className="text-sm font-medium text-slate-500">{label}</p>
        {icon && <span className={cx('flex size-9 items-center justify-center rounded-xl transition-transform duration-300 group-hover:rotate-6 group-hover:scale-110', TONES[tone].chip)} aria-hidden>{icon}</span>}
      </div>
      <p className={cx('mt-2 font-display text-[26px] font-bold tabular-nums tracking-tight', valueClass ?? 'text-slate-900')}>
        <Flash value={flashValue}>{value}</Flash>
      </p>
      {sub && <div className="mt-1 text-sm tabular-nums text-slate-500">{sub}</div>}
    </div>
  );
}

// ---------------------------------------------------------------- controls
type Variant = 'primary' | 'secondary' | 'danger' | 'ghost' | 'buy' | 'sell';

const variants: Record<Variant, string> = {
  primary: 'bg-indigo-600 text-white shadow-sm shadow-indigo-600/25 hover:brightness-110 focus-visible:outline-indigo-600',
  secondary: 'border border-slate-200 bg-surface text-slate-700 shadow-sm hover:border-slate-300 hover:bg-slate-50 focus-visible:outline-indigo-600',
  danger: 'bg-red-600 text-white shadow-sm shadow-red-600/20 hover:brightness-110 focus-visible:outline-red-600',
  ghost: 'text-slate-600 hover:bg-slate-100 hover:text-slate-900 focus-visible:outline-indigo-600',
  buy: 'bg-emerald-600 text-white shadow-sm shadow-emerald-600/25 hover:brightness-110 focus-visible:outline-emerald-600',
  sell: 'bg-red-600 text-white shadow-sm shadow-red-600/20 hover:brightness-110 focus-visible:outline-red-600',
};

export function Button({ variant = 'primary', size = 'md', loading, className, children, disabled, ...rest }:
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: 'sm' | 'md'; loading?: boolean }) {
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      className={cx(
        'inline-flex items-center justify-center gap-2 rounded-xl font-semibold transition duration-150 active:scale-[0.97] disabled:active:scale-100',
        'focus-visible:outline-2 focus-visible:outline-offset-2 disabled:cursor-not-allowed disabled:opacity-50',
        size === 'sm' ? 'px-2.5 py-1.5 text-xs' : 'px-4 py-2 text-sm',
        variants[variant], className,
      )}
    >
      {loading && <LoaderCircle className="size-4 animate-spin" aria-hidden />}
      {children}
    </button>
  );
}

const inputClass = 'block w-full rounded-xl border border-slate-200 bg-surface px-3 py-2 text-sm text-slate-900 shadow-sm transition '
  + 'placeholder:text-slate-400 hover:border-slate-300 focus:border-indigo-500 focus:outline-none focus:ring-3 focus:ring-indigo-500/15 '
  + 'disabled:bg-slate-50 aria-[invalid=true]:border-rose-400 aria-[invalid=true]:ring-rose-100';

export function Field({ label, error, hint, children, htmlFor }: {
  label: string; error?: string; hint?: ReactNode; children: ReactNode; htmlFor?: string;
}) {
  return (
    <div>
      <label htmlFor={htmlFor} className="mb-1.5 block text-sm font-medium text-slate-700">{label}</label>
      {children}
      {error ? <p className="mt-1 text-xs text-rose-600" role="alert">{error}</p>
        : hint ? <p className="mt-1 text-xs text-slate-500">{hint}</p> : null}
    </div>
  );
}

export function TextInput({ label, error, hint, ...rest }: InputHTMLAttributes<HTMLInputElement> & {
  label: string; error?: string; hint?: ReactNode;
}) {
  const id = useId();
  return (
    <Field label={label} error={error} hint={hint} htmlFor={rest.id ?? id}>
      <input id={rest.id ?? id} aria-invalid={error ? true : undefined} className={inputClass} {...rest} />
    </Field>
  );
}

export function SelectInput({ label, error, children, ...rest }: SelectHTMLAttributes<HTMLSelectElement> & {
  label: string; error?: string;
}) {
  const id = useId();
  return (
    <Field label={label} error={error} htmlFor={rest.id ?? id}>
      <select id={rest.id ?? id} aria-invalid={error ? true : undefined} className={inputClass} {...rest}>{children}</select>
    </Field>
  );
}

export function Segmented<T extends string>({ value, options, onChange, label }: {
  value: T; options: { value: T; label: string; activeClass?: string }[]; onChange: (v: T) => void; label: string;
}) {
  // The selected option sits on a pill that slides between options.
  const index = Math.max(0, options.findIndex((o) => o.value === value));
  const active = options[index];
  return (
    <div role="radiogroup" aria-label={label} className="relative grid auto-cols-fr grid-flow-col gap-1 rounded-xl bg-slate-100 p-1">
      <span aria-hidden
        className={cx('absolute inset-y-1 left-1 rounded-md transition-all duration-300 ease-[cubic-bezier(0.22,1,0.36,1)]',
          active?.activeClass ?? 'bg-surface shadow-sm')}
        style={{
          width: `calc((100% - 0.5rem - ${options.length - 1} * 0.25rem) / ${options.length})`,
          transform: `translateX(calc(${index} * (100% + 0.25rem)))`,
        }} />
      {options.map((o) => (
        <button
          key={o.value} type="button" role="radio" aria-checked={value === o.value}
          onClick={() => onChange(o.value)}
          className={cx('relative rounded-md px-3 py-1.5 text-sm font-medium transition-colors duration-300',
            value === o.value ? (o.activeClass ? 'text-white' : 'text-slate-900') : 'text-slate-500 hover:text-slate-900')}
        >
          {o.label}
        </button>
      ))}
    </div>
  );
}

export function Tabs<T extends string>({ value, tabs, onChange }: {
  value: T; tabs: { value: T; label: string; count?: number }[]; onChange: (v: T) => void;
}) {
  return (
    <div role="tablist" className="mb-4 flex gap-1 overflow-x-auto border-b border-slate-200">
      {tabs.map((t) => (
        <button
          key={t.value} role="tab" aria-selected={value === t.value} onClick={() => onChange(t.value)}
          className={cx('-mb-px whitespace-nowrap border-b-2 px-4 py-2.5 text-sm font-medium transition-colors',
            value === t.value ? 'border-indigo-600 text-slate-900' : 'border-transparent text-slate-500 hover:border-slate-300 hover:text-slate-800')}
        >
          {t.label}
          {t.count != null && <span className={cx('ml-2 rounded-full px-2 py-0.5 text-xs transition-colors',
            value === t.value ? 'bg-indigo-50 text-indigo-700' : 'bg-slate-100 text-slate-600')}>{t.count}</span>}
        </button>
      ))}
    </div>
  );
}

// ---------------------------------------------------------------- states
export function Spinner({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="flex items-center justify-center gap-2 py-10 text-sm text-slate-500" role="status">
      <LoaderCircle className="size-5 animate-spin" aria-hidden /> {label}
    </div>
  );
}

export function Skeleton({ className }: { className?: string }) {
  return <div className={cx('skeleton-shimmer animate-shimmer rounded-xl', className)} />;
}

export function ErrorState({ error, onRetry, compact }: { error: ApiError | Error; onRetry?: () => void; compact?: boolean }) {
  return (
    <div className={cx('flex flex-col items-center justify-center gap-3 text-center', compact ? 'py-4' : 'py-10')} role="alert">
      <TriangleAlert className="size-6 text-red-500" aria-hidden />
      <div>
        <p className="text-sm font-medium text-slate-800">Couldn't load this data</p>
        <p className="mt-0.5 text-sm text-slate-500">{error.message}</p>
      </div>
      {onRetry && <Button variant="secondary" size="sm" onClick={onRetry}><RefreshCw className="size-3.5" /> Retry</Button>}
    </div>
  );
}

export function EmptyState({ title, children, icon, tone = 'indigo' }: { title: string; children?: ReactNode; icon?: ReactNode; tone?: Tone }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-10 text-center">
      {/* A small friendly illustration: the icon in a soft blob with two orbiting dots. */}
      <div className="relative mb-2" aria-hidden>
        <span className={cx('absolute -right-2 -top-1 size-2.5 rounded-full opacity-70', TONES[tone].chip)} />
        <span className={cx('absolute -bottom-1 -left-3 size-2 rounded-full opacity-50', TONES[tone].chip)} />
        <div className={cx('flex size-16 items-center justify-center rounded-[1.4rem] rotate-3', TONES[tone].chip)}>
          <span className="-rotate-3">{icon ?? <Inbox className="size-7" />}</span>
        </div>
      </div>
      <p className="text-sm font-medium text-slate-700">{title}</p>
      {children && <div className="max-w-sm text-sm text-slate-500">{children}</div>}
    </div>
  );
}

export function Alert({ tone = 'info', children, title }: { tone?: 'info' | 'error' | 'success' | 'warning'; children: ReactNode; title?: string }) {
  const tones = {
    info: 'border-indigo-100 bg-indigo-50 text-indigo-900',
    error: 'border-red-200 bg-red-50 text-red-900',
    success: 'border-emerald-200 bg-emerald-50 text-emerald-900',
    warning: 'border-amber-200 bg-amber-50 text-amber-900',
  };
  const Icon = tone === 'success' ? CircleCheck : tone === 'info' ? Info : TriangleAlert;
  return (
    <div className={cx('flex gap-3 rounded-xl border px-4 py-3 text-sm', tones[tone])} role={tone === 'error' ? 'alert' : 'status'}>
      <Icon className="mt-0.5 size-4 shrink-0" aria-hidden />
      <div>{title && <p className="font-medium">{title}</p>}{children}</div>
    </div>
  );
}

export function Badge({ children, className }: { children: ReactNode; className?: string }) {
  return <span className={cx('inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium', className)}>{children}</span>;
}

// ---------------------------------------------------------------- dialogs
export function Modal({ open, onClose, title, children, footer }: {
  open: boolean; onClose: () => void; title: string; children: ReactNode; footer?: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    document.addEventListener('keydown', onKey);
    ref.current?.focus();
    return () => document.removeEventListener('keydown', onKey);
  }, [open, onClose]);
  if (!open) return null;
  // Portalled to <body> so no transformed ancestor (page entrance animation) can clip the overlay.
  return createPortal(
    <div className="fixed inset-0 z-50 flex animate-fade items-end justify-center bg-slate-900/50 p-4 sm:items-center" onMouseDown={onClose}>
      <div
        ref={ref} tabIndex={-1} role="dialog" aria-modal="true" aria-label={title}
        className="w-full max-w-md animate-pop rounded-2xl bg-surface shadow-2xl ring-1 ring-slate-900/10 outline-none"
        onMouseDown={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <h2 className="text-base font-semibold text-slate-900">{title}</h2>
          <button onClick={onClose} className="rounded-md p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600" aria-label="Close">
            <X className="size-4" />
          </button>
        </div>
        <div className="px-5 py-4 text-sm text-slate-700">{children}</div>
        {footer && <div className="flex justify-end gap-2 border-t border-slate-100 px-5 py-3">{footer}</div>}
      </div>
    </div>,
    document.body,
  );
}

export function ConfirmDialog({ open, title, children, confirmLabel, variant = 'primary', busy, error, onConfirm, onCancel }: {
  open: boolean; title: string; children: ReactNode; confirmLabel: string; variant?: Variant; busy?: boolean;
  error?: string | null; onConfirm: () => void; onCancel: () => void;
}) {
  return (
    <Modal
      open={open} onClose={busy ? () => {} : onCancel} title={title}
      footer={<>
        <Button variant="secondary" onClick={onCancel} disabled={busy}>Back</Button>
        <Button variant={variant} onClick={onConfirm} loading={busy}>{confirmLabel}</Button>
      </>}
    >
      {children}
      {error && <div className="mt-3"><Alert tone="error">{error}</Alert></div>}
    </Modal>
  );
}

// ---------------------------------------------------------------- toasts
interface Toast { id: number; tone: 'success' | 'error' | 'info'; message: string }
const ToastContext = createContext<(tone: Toast['tone'], message: string) => void>(() => {});

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const push = useCallback((tone: Toast['tone'], message: string) => {
    const id = Date.now() + Math.random();
    setToasts((t) => [...t, { id, tone, message }]);
    window.setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 5000);
  }, []);
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="pointer-events-none fixed bottom-4 right-4 z-[60] flex w-80 max-w-[calc(100vw-2rem)] flex-col gap-2" aria-live="polite">
        {toasts.map((t) => (
          <div key={t.id} className={cx('pointer-events-auto flex animate-toast items-start gap-2 rounded-lg px-4 py-3 text-sm text-white shadow-lg',
            t.tone === 'success' ? 'bg-emerald-700' : t.tone === 'error' ? 'bg-red-700' : 'bg-slate-800')}>
            {t.tone === 'success' ? <CircleCheck className="mt-0.5 size-4 shrink-0" /> : t.tone === 'error'
              ? <TriangleAlert className="mt-0.5 size-4 shrink-0" /> : <Info className="mt-0.5 size-4 shrink-0" />}
            <span>{t.message}</span>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

export const useToast = () => useContext(ToastContext);
