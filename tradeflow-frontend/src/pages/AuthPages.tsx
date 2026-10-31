import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { Activity, ShieldCheck, Zap } from 'lucide-react';
import { ApiError } from '../api/client';
import type { RegisterRequest } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Logo } from '../components/Layout';
import { Alert, Button, SelectInput, TextInput } from '../components/ui';

function AuthShell({ title, subtitle, children }: { title: string; subtitle: ReactNode; children: ReactNode }) {
  return (
    <div className="grid min-h-dvh lg:grid-cols-[1fr_minmax(0,560px)]">
      <div className="relative hidden flex-col justify-between overflow-hidden bg-[#0b1220] p-12 text-slate-300 lg:flex">
        <Logo />
        <div className="max-w-md">
          <h2 className="text-3xl font-semibold leading-tight text-white">Event-driven paper trading on a simulated market.</h2>
          <ul className="mt-8 space-y-4 text-sm">
            <li className="flex gap-3"><Zap className="size-5 shrink-0 text-emerald-400" />Market and limit orders, settled through a Kafka saga across microservices.</li>
            <li className="flex gap-3"><Activity className="size-5 shrink-0 text-emerald-400" />Portfolio value, P&amp;L, risk and performance computed from your ledger.</li>
            <li className="flex gap-3"><ShieldCheck className="size-5 shrink-0 text-emerald-400" />Every request authenticated at the API gateway with a signed JWT.</li>
          </ul>
        </div>
        <p className="text-xs text-slate-500">All prices are simulated demo data. Not investment advice.</p>
      </div>
      <div className="flex items-center justify-center px-4 py-12 sm:px-8">
        <div className="w-full max-w-sm">
          <div className="mb-8 lg:hidden"><Logo dark={false} /></div>
          <h1 className="text-2xl font-semibold tracking-tight text-slate-900">{title}</h1>
          <p className="mt-1.5 text-sm text-slate-500">{subtitle}</p>
          <div className="mt-8">{children}</div>
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
