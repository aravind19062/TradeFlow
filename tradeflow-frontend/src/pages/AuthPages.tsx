import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { GraduationCap, ShieldCheck, Zap } from 'lucide-react';
import { ApiError } from '../api/client';
import type { RegisterRequest } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Logo } from '../components/Layout';
import { Alert, Button, SelectInput, TextInput } from '../components/ui';

function AuthShell({ title, subtitle, children }: { title: string; subtitle: ReactNode; children: ReactNode }) {
  const features = [
    { icon: GraduationCap, title: 'Learn', text: 'Trading Academy lessons, exercises and quizzes, then a readiness assessment.' },
    { icon: Zap, title: 'Practice', text: 'Market, limit and stop orders on a simulated market with virtual money.' },
    { icon: ShieldCheck, title: 'Trade responsibly', text: 'Live trading stays separate, behind readiness, limits and two-factor step-up.' },
  ];
  return (
    <div className="grid min-h-dvh lg:grid-cols-[1fr_minmax(0,560px)]">
      <div className="relative hidden flex-col justify-between overflow-hidden bg-ink p-12 text-slate-300 lg:flex">
        <div className="bg-grid pointer-events-none absolute inset-0" aria-hidden />
        <div className="pointer-events-none absolute -left-32 top-1/4 size-[28rem] animate-float rounded-full bg-emerald-500/20 blur-[100px]" aria-hidden />
        <div className="pointer-events-none absolute -right-20 bottom-0 size-[24rem] animate-float rounded-full bg-indigo-500/20 blur-[100px] [animation-delay:-7s]" aria-hidden />
        <div className="pointer-events-none absolute right-1/4 top-0 size-64 animate-float rounded-full bg-cyan-400/10 blur-[80px] [animation-delay:-3s]" aria-hidden />

        <div className="relative animate-enter"><Logo /></div>
        <div className="relative max-w-lg">
          <p className="mb-4 inline-flex animate-enter items-center gap-2 rounded-full border border-white/10 bg-white/5 px-3 py-1 text-xs font-medium text-slate-300 backdrop-blur">
            <span className="size-1.5 rounded-full bg-emerald-400 shadow-[0_0_8px_#34d399]" /> Learn. Practice. Trade responsibly.
          </p>
          <h2 className="animate-enter text-5xl font-semibold leading-[1.08] tracking-tight text-white [animation-delay:80ms]">
            Build skill before <span className="text-gradient">real money</span> is on the line.
          </h2>
          <ul className="stagger mt-10 space-y-3 text-sm">
            {features.map(({ icon: Icon, title, text }) => (
              <li key={title} className="group flex gap-4 rounded-2xl border border-white/5 bg-white/[0.03] p-4 backdrop-blur transition-all duration-300 hover:-translate-y-0.5 hover:border-emerald-400/20 hover:bg-white/[0.06]">
                <span className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-emerald-400/20 to-cyan-500/20 text-emerald-300 ring-1 ring-emerald-400/20 transition-transform duration-300 group-hover:scale-110">
                  <Icon className="size-5" />
                </span>
                <span><span className="block font-semibold text-white">{title}</span>{text}</span>
              </li>
            ))}
          </ul>
        </div>
        <p className="relative text-xs text-slate-500">Demo prices are simulated. Not investment advice.</p>
      </div>
      <div className="relative flex items-center justify-center px-4 py-12 sm:px-8">
        <div className="w-full max-w-sm animate-enter">
          <div className="mb-8 lg:hidden"><Logo dark={false} /></div>
          <h1 className="text-3xl font-semibold tracking-tight text-slate-900">{title}</h1>
          <p className="mt-1.5 text-sm text-slate-500">{subtitle}</p>
          <div className="mt-8 rounded-2xl border border-slate-200/80 bg-white/80 p-6 shadow-soft backdrop-blur">{children}</div>
        </div>
      </div>
    </div>
  );
}

export function LoginPage() {
  const { login, completeMfa, logoutReason } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const from = (location.state as { from?: string } | null)?.from ?? '/';
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [mfa, setMfa] = useState<{ token: string } | null>(null);
  const [useRecovery, setUseRecovery] = useState(false);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const fail = (err: unknown) => setError(err instanceof ApiError ? err : new ApiError(0, 'CLIENT_ERROR', String(err)));

  async function onPassword(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const outcome = await login({ email: email.trim(), password });
      setPassword('');
      if (outcome.mfaRequired) {
        setMfa({ token: outcome.mfaToken });
      } else {
        navigate(from, { replace: true });
      }
    } catch (err) {
      fail(err);
    } finally {
      setBusy(false);
    }
  }

  async function onSecondFactor(e: FormEvent) {
    e.preventDefault();
    if (!mfa) return;
    setBusy(true);
    setError(null);
    try {
      await completeMfa(mfa.token, useRecovery ? { recoveryCode: code.trim() } : { code: code.trim() });
      navigate(from, { replace: true });
    } catch (err) {
      fail(err);
      setCode('');
      if (err instanceof ApiError && err.code === 'MFA_CHALLENGE_EXPIRED') setMfa(null);
    } finally {
      setBusy(false);
    }
  }

  const errorText = error && (error.code === 'TOO_MANY_LOGIN_ATTEMPTS' && error.retryAfterSeconds
    ? `Too many failed attempts. Try again in ${Math.ceil(error.retryAfterSeconds / 60)} minute(s).`
    : error.message);

  if (mfa) {
    return (
      <AuthShell title="Two-factor verification" subtitle={useRecovery
        ? 'Enter one of your one-time recovery codes.'
        : 'Enter the 6-digit code from your authenticator app.'}>
        {errorText && <div className="mb-4"><Alert tone="error">{errorText}</Alert></div>}
        <form onSubmit={onSecondFactor} className="space-y-4" noValidate>
          {useRecovery
            ? <TextInput label="Recovery code" value={code} onChange={(e) => setCode(e.target.value)} autoComplete="off"
                placeholder="XXXXX-XXXXX" autoFocus />
            : <TextInput label="Verification code" value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
                inputMode="numeric" autoComplete="one-time-code" placeholder="123456" autoFocus />}
          <Button type="submit" className="w-full" loading={busy}>Verify and sign in</Button>
        </form>
        <div className="mt-4 flex justify-between text-sm">
          <button className="font-medium text-sky-700 hover:underline" onClick={() => { setUseRecovery((r) => !r); setCode(''); setError(null); }}>
            {useRecovery ? 'Use authenticator code' : 'Use a recovery code'}
          </button>
          <button className="text-slate-500 hover:underline" onClick={() => { setMfa(null); setCode(''); setError(null); }}>Start over</button>
        </div>
        <p className="mt-6 text-xs text-slate-500">
          Lost your device and your recovery codes? Contact support. An administrator can reset two-factor
          authentication after verifying your identity; live trading is then paused for 24 hours.
        </p>
      </AuthShell>
    );
  }

  return (
    <AuthShell title="Sign in" subtitle={<>New to TradeFlow? <Link to="/register" className="font-medium text-sky-700 hover:underline">Create an account</Link></>}>
      {logoutReason === 'expired' && !error && (
        <div className="mb-4"><Alert tone="warning">Your session has ended. Please sign in again.</Alert></div>
      )}
      {errorText && <div className="mb-4"><Alert tone="error">{errorText}</Alert></div>}
      <form onSubmit={onPassword} className="space-y-4" noValidate>
        <TextInput label="Email" type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)}
          required error={error?.fieldError('email')} />
        <TextInput label="Password" type="password" autoComplete="current-password" value={password}
          onChange={(e) => setPassword(e.target.value)} required error={error?.fieldError('password')} />
        <Button type="submit" className="w-full" loading={busy}>Sign in</Button>
      </form>
    </AuthShell>
  );
}

type RegisterForm = Required<Pick<RegisterRequest, 'name' | 'email' | 'password'>> & {
  dateOfBirth: string; gender: string; mobileNumber: string; occupation: string;
  maritalStatus: string; incomeRange: string;
};

const EMPTY: RegisterForm = {
  name: '', email: '', password: '', dateOfBirth: '', gender: '', mobileNumber: '',
  occupation: '', maritalStatus: '', incomeRange: '',
};

export function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [form, setForm] = useState<RegisterForm>(EMPTY);
  const [showOptional, setShowOptional] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);
  const set = (k: keyof RegisterForm) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [k]: e.target.value }));

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    // Optional fields are omitted when blank (the backend validates their format only when present).
    const body: RegisterRequest = Object.fromEntries(
      Object.entries({ ...form, email: form.email.trim(), name: form.name.trim() })
        .filter(([, v]) => v !== ''),
    ) as unknown as RegisterRequest;
    try {
      await register(body);
      navigate('/', { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError(0, 'CLIENT_ERROR', String(err)));
    } finally {
      setBusy(false);
    }
  }

  const fe = (f: string) => error?.fieldError(f);

  return (
    <AuthShell title="Create your account" subtitle={<>Already registered? <Link to="/login" className="font-medium text-sky-700 hover:underline">Sign in</Link></>}>
      {error && (
        <div className="mb-4">
          <Alert tone="error">{error.code === 'VALIDATION_FAILED' ? 'Please fix the highlighted fields.' : error.message}</Alert>
        </div>
      )}
      <form onSubmit={onSubmit} className="space-y-4" noValidate>
        <TextInput label="Full name" autoComplete="name" value={form.name} onChange={set('name')} maxLength={100} error={fe('name')} />
        <TextInput label="Email" type="email" autoComplete="email" value={form.email} onChange={set('email')} error={fe('email')} />
        <TextInput label="Password" type="password" autoComplete="new-password" value={form.password} onChange={set('password')}
          maxLength={72} hint="8–72 characters" error={fe('password')} />

        <button type="button" className="text-sm font-medium text-sky-700 hover:underline" onClick={() => setShowOptional((s) => !s)}>
          {showOptional ? 'Hide' : 'Add'} optional profile details
        </button>
        {showOptional && (
          <div className="grid gap-4 rounded-lg border border-slate-200 p-4 sm:grid-cols-2">
            <TextInput label="Date of birth" type="date" value={form.dateOfBirth} onChange={set('dateOfBirth')} error={fe('dateOfBirth')} />
            <TextInput label="Mobile number" inputMode="tel" value={form.mobileNumber} onChange={set('mobileNumber')}
              placeholder="+919876543210" error={fe('mobileNumber')} />
            <SelectInput label="Gender" value={form.gender} onChange={set('gender')} error={fe('gender')}>
              <option value="">Prefer not to say</option>
              <option>Female</option><option>Male</option><option>Other</option>
            </SelectInput>
            <TextInput label="Occupation" value={form.occupation} onChange={set('occupation')} maxLength={100} error={fe('occupation')} />
            <TextInput label="Marital status" value={form.maritalStatus} onChange={set('maritalStatus')} maxLength={20} error={fe('maritalStatus')} />
            <div className="sm:col-span-2">
              <TextInput label="Income range" value={form.incomeRange} onChange={set('incomeRange')} maxLength={50} error={fe('incomeRange')} />
            </div>
          </div>
        )}
        <Button type="submit" className="w-full" loading={busy}>Create account</Button>
        <p className="text-xs text-slate-500">A paper-trading cash account is opened for you automatically right after registration.</p>
      </form>
    </AuthShell>
  );
}
