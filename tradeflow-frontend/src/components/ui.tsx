import {
  createContext, useCallback, useContext, useEffect, useId, useRef, useState,
  type ButtonHTMLAttributes, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes,
} from 'react';
import { CircleCheck, Inbox, Info, LoaderCircle, RefreshCw, TriangleAlert, X } from 'lucide-react';
import type { ApiError } from '../api/client';

export function cx(...classes: (string | false | null | undefined)[]): string {
  return classes.filter(Boolean).join(' ');
}

// ---------------------------------------------------------------- layout
export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight text-slate-900">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-slate-500">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

export function Card({ title, action, children, className, bodyClassName }: {
  title?: ReactNode; action?: ReactNode; children: ReactNode; className?: string; bodyClassName?: string;
}) {
  return (
    <section className={cx('rounded-xl border border-slate-200 bg-white shadow-sm', className)}>
      {(title || action) && (
        <header className="flex items-center justify-between gap-3 border-b border-slate-100 px-5 py-3.5">
          <h2 className="text-sm font-semibold text-slate-800">{title}</h2>
          {action}
        </header>
      )}
      <div className={cx('p-5', bodyClassName)}>{children}</div>
    </section>
  );
}

export function Stat({ label, value, sub, valueClass, testId }: {
  label: string; value: ReactNode; sub?: ReactNode; valueClass?: string; testId?: string;
}) {
  return (
    <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm" data-testid={testId}>
      <p className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</p>
      <p className={cx('mt-2 text-2xl font-semibold tabular-nums tracking-tight', valueClass ?? 'text-slate-900')}>{value}</p>
      {sub && <div className="mt-1 text-sm tabular-nums text-slate-500">{sub}</div>}
    </div>
  );
}

// ---------------------------------------------------------------- controls
type Variant = 'primary' | 'secondary' | 'danger' | 'ghost' | 'buy' | 'sell';

const variants: Record<Variant, string> = {
  primary: 'bg-slate-900 text-white hover:bg-slate-800 focus-visible:outline-slate-900',
  secondary: 'border border-slate-300 bg-white text-slate-700 hover:bg-slate-50 focus-visible:outline-slate-400',
  danger: 'bg-rose-600 text-white hover:bg-rose-700 focus-visible:outline-rose-600',
  ghost: 'text-slate-600 hover:bg-slate-100 focus-visible:outline-slate-400',
  buy: 'bg-emerald-600 text-white hover:bg-emerald-700 focus-visible:outline-emerald-600',
  sell: 'bg-rose-600 text-white hover:bg-rose-700 focus-visible:outline-rose-600',
};

export function Button({ variant = 'primary', size = 'md', loading, className, children, disabled, ...rest }:
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: 'sm' | 'md'; loading?: boolean }) {
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      className={cx(
        'inline-flex items-center justify-center gap-2 rounded-lg font-medium transition-colors',
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

const inputClass = 'block w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 shadow-sm '
  + 'placeholder:text-slate-400 focus:border-slate-500 focus:outline-none focus:ring-2 focus:ring-slate-200 '
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
  return (
    <div role="radiogroup" aria-label={label} className="grid grid-flow-col gap-1 rounded-lg bg-slate-100 p-1">
      {options.map((o) => (
        <button
          key={o.value} type="button" role="radio" aria-checked={value === o.value}
          onClick={() => onChange(o.value)}
          className={cx('rounded-md px-3 py-1.5 text-sm font-medium transition-colors',
            value === o.value ? (o.activeClass ?? 'bg-white text-slate-900 shadow-sm') : 'text-slate-600 hover:text-slate-900')}
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
          className={cx('-mb-px whitespace-nowrap border-b-2 px-4 py-2.5 text-sm font-medium',
            value === t.value ? 'border-slate-900 text-slate-900' : 'border-transparent text-slate-500 hover:text-slate-800')}
        >
          {t.label}
          {t.count != null && <span className="ml-2 rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-600">{t.count}</span>}
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
  return <div className={cx('animate-pulse rounded-md bg-slate-200/70', className)} />;
}

export function ErrorState({ error, onRetry, compact }: { error: ApiError | Error; onRetry?: () => void; compact?: boolean }) {
  return (
    <div className={cx('flex flex-col items-center justify-center gap-3 text-center', compact ? 'py-4' : 'py-10')} role="alert">
      <TriangleAlert className="size-6 text-rose-500" aria-hidden />
      <div>
        <p className="text-sm font-medium text-slate-800">Couldn't load this data</p>
        <p className="mt-0.5 text-sm text-slate-500">{error.message}</p>
      </div>
      {onRetry && <Button variant="secondary" size="sm" onClick={onRetry}><RefreshCw className="size-3.5" /> Retry</Button>}
    </div>
  );
}

export function EmptyState({ title, children, icon }: { title: string; children?: ReactNode; icon?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 py-10 text-center">
      <div className="text-slate-300">{icon ?? <Inbox className="size-8" aria-hidden />}</div>
      <p className="text-sm font-medium text-slate-700">{title}</p>
      {children && <div className="max-w-sm text-sm text-slate-500">{children}</div>}
    </div>
  );
}

export function Alert({ tone = 'info', children, title }: { tone?: 'info' | 'error' | 'success' | 'warning'; children: ReactNode; title?: string }) {
  const tones = {
    info: 'border-sky-200 bg-sky-50 text-sky-900',
    error: 'border-rose-200 bg-rose-50 text-rose-900',
    success: 'border-emerald-200 bg-emerald-50 text-emerald-900',
    warning: 'border-amber-200 bg-amber-50 text-amber-900',
  };
  const Icon = tone === 'success' ? CircleCheck : tone === 'info' ? Info : TriangleAlert;
  return (
    <div className={cx('flex gap-3 rounded-lg border px-4 py-3 text-sm', tones[tone])} role={tone === 'error' ? 'alert' : 'status'}>
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
  return (
    <div className="fixed inset-0 z-50 flex items-end justify-center bg-slate-950/50 p-4 sm:items-center" onMouseDown={onClose}>
      <div
        ref={ref} tabIndex={-1} role="dialog" aria-modal="true" aria-label={title}
        className="w-full max-w-md rounded-xl bg-white shadow-xl outline-none"
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
    </div>
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
          <div key={t.id} className={cx('pointer-events-auto flex items-start gap-2 rounded-lg px-4 py-3 text-sm text-white shadow-lg',
            t.tone === 'success' ? 'bg-emerald-700' : t.tone === 'error' ? 'bg-rose-700' : 'bg-slate-800')}>
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
