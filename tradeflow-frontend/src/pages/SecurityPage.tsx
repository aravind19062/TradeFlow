import { useEffect, useState, type FormEvent } from 'react';
import QRCode from 'qrcode';
import { Copy, Download, KeyRound, LogOut, ShieldCheck, ShieldOff, Smartphone } from 'lucide-react';
import { ApiError } from '../api/client';
import { authApi, mfaApi } from '../api/endpoints';
import type { MfaEnrollment } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Alert, Badge, Button, Card, ConfirmDialog, ErrorState, PageHeader, Spinner, TextInput, useToast } from '../components/ui';
import { dateTime } from '../lib/format';
import { useApi } from '../lib/useApi';

const message = (e: unknown) => (e instanceof ApiError ? e.message : String(e));

const EVENT_LABELS: Record<string, string> = {
  LOGIN_SUCCEEDED: 'Signed in', LOGIN_FAILED: 'Failed sign-in attempt', MFA_CHALLENGE_FAILED: 'Wrong verification code',
  RECOVERY_CODE_USED: 'Recovery code used', MFA_ENROLLMENT_STARTED: 'Two-factor setup started', MFA_ENABLED: 'Two-factor enabled',
  MFA_DISABLED: 'Two-factor disabled', RECOVERY_CODES_REGENERATED: 'Recovery codes replaced',
  MFA_RESET_BY_ADMIN: 'Two-factor reset by an administrator', STEP_UP_SUCCEEDED: 'Live-trading verification',
  STEP_UP_FAILED: 'Failed live-trading verification', REFRESH_REUSE_DETECTED: 'Session ended: possible token theft',
  SIGNED_OUT: 'Signed out', SIGNED_OUT_EVERYWHERE: 'Signed out of all devices',
};

/** Two-factor authentication (TOTP), recovery codes, sessions and recent account activity. */
export function SecurityPage() {
  const status = useApi(() => mfaApi.status(), []);
  const activity = useApi(() => mfaApi.securityEvents(), []);
  const reload = () => { status.reload(); activity.reload(); };

  return (
    <>
      <PageHeader title="Security" subtitle="Two-factor authentication is required before live trading can be enabled." />
      <div className="grid gap-6 lg:grid-cols-2">
        <Card title={<span className="flex items-center gap-2"><Smartphone className="size-4" /> Two-factor authentication</span>}
          action={status.data && (status.data.enabled
            ? <Badge className="bg-emerald-50 text-emerald-700 ring-1 ring-emerald-200"><ShieldCheck className="size-3" /> On</Badge>
            : <Badge className="bg-slate-100 text-slate-600 ring-1 ring-slate-200">Off</Badge>)}>
          {status.error ? <ErrorState error={status.error} onRetry={status.reload} compact />
            : !status.data ? <Spinner />
              : status.data.enabled ? <ManageMfa remaining={status.data.recoveryCodesRemaining} enabledAt={status.data.enabledAt} onChange={reload} />
                : <EnrollMfa onDone={reload} />}
        </Card>
        <div className="space-y-6">
          <SessionsCard />
          <Card title="Recent security activity" bodyClassName="p-0">
            {activity.error ? <ErrorState error={activity.error} onRetry={activity.reload} compact />
              : !activity.data ? <Spinner />
                : <ul className="max-h-96 divide-y divide-slate-100 overflow-y-auto text-sm" data-testid="security-activity">
                  {activity.data.map((e, i) => (
                    <li key={i} className="flex items-start justify-between gap-3 px-5 py-2.5">
                      <div>
                        <p className={e.type.includes('FAILED') || e.type.includes('REUSE') || e.byAdministrator ? 'font-medium text-rose-700' : 'text-slate-800'}>
                          {EVENT_LABELS[e.type] ?? e.type}
                        </p>
                        <p className="text-xs text-slate-400">{e.clientIp ?? ''}</p>
                      </div>
                      <span className="whitespace-nowrap text-xs text-slate-500">{dateTime(e.occurredAt)}</span>
                    </li>
                  ))}
                </ul>}
          </Card>
        </div>
      </div>
    </>
  );
}

function EnrollMfa({ onDone }: { onDone: () => void }) {
  const [password, setPassword] = useState('');
  const [enrollment, setEnrollment] = useState<MfaEnrollment | null>(null);
  const [qr, setQr] = useState<string | null>(null);
  const [code, setCode] = useState('');
  const [codes, setCodes] = useState<string[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!enrollment) return;
    QRCode.toDataURL(enrollment.otpauthUri, { margin: 1, width: 200 }).then(setQr).catch(() => setQr(null));
  }, [enrollment]);

  async function start(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError(null);
    try { setEnrollment(await mfaApi.enroll(password)); setPassword(''); } catch (err) { setError(message(err)); } finally { setBusy(false); }
  }

  async function confirm(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError(null);
    try { setCodes((await mfaApi.confirm(code)).recoveryCodes); } catch (err) { setError(message(err)); setCode(''); } finally { setBusy(false); }
  }

  if (codes) {
    return <RecoveryCodes codes={codes} onDone={onDone} />;
  }
  if (enrollment) {
    return (
      <form onSubmit={confirm} className="space-y-4" noValidate>
        <ol className="list-decimal space-y-1 pl-5 text-sm text-slate-600">
          <li>Scan this code with an authenticator app (Google Authenticator, Microsoft Authenticator, 1Password, …).</li>
          <li>Enter the 6-digit code it shows.</li>
        </ol>
        <div className="flex flex-col items-center gap-3 rounded-lg bg-slate-50 p-4 sm:flex-row sm:items-start">
          {qr ? <img src={qr} alt="QR code for your authenticator app" className="size-44 rounded bg-white p-1" /> : <Spinner />}
          <div className="text-xs text-slate-500">
            <p>Can't scan? Enter this key manually:</p>
            <code className="mt-1 block break-all rounded bg-white px-2 py-1 font-mono text-sm text-slate-800" data-testid="totp-secret">
              {enrollment.secret.match(/.{1,4}/g)?.join(' ')}
            </code>
            <p className="mt-2">This key is shown only now. Never share it.</p>
          </div>
        </div>
        <TextInput label="Code from your app" value={code} inputMode="numeric" autoComplete="one-time-code"
          onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))} placeholder="123456" error={error ?? undefined} />
        <Button type="submit" loading={busy} disabled={code.length !== 6}>Turn on two-factor authentication</Button>
      </form>
    );
  }
  return (
    <form onSubmit={start} className="space-y-4" noValidate>
      <p className="text-sm text-slate-600">
        Protect your account with a time-based code from an authenticator app. You will need it to sign in and to
        authorise live trading.
      </p>
      <TextInput label="Confirm your password" type="password" autoComplete="current-password" value={password}
        onChange={(e) => setPassword(e.target.value)} error={error ?? undefined} />
      <Button type="submit" loading={busy} disabled={!password}><KeyRound className="size-4" /> Set up authenticator</Button>
    </form>
  );
}

function RecoveryCodes({ codes, onDone }: { codes: string[]; onDone: () => void }) {
  const toast = useToast();
  const [saved, setSaved] = useState(false);
  const text = `TradeFlow recovery codes (each works once)\n${codes.join('\n')}\n`;
  return (
    <div className="space-y-4">
      <Alert tone="warning" title="Save your recovery codes now">
        They are shown only once. Each code can replace an authenticator code one time if you lose your device.
      </Alert>
      <ul className="grid grid-cols-2 gap-2 rounded-lg bg-slate-50 p-4 font-mono text-sm" data-testid="recovery-codes">
        {codes.map((c) => <li key={c}>{c}</li>)}
      </ul>
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" size="sm" onClick={() => navigator.clipboard?.writeText(text).then(() => toast('success', 'Copied'))}>
          <Copy className="size-3.5" /> Copy
        </Button>
        <Button variant="secondary" size="sm" onClick={() => {
          const a = document.createElement('a');
          a.href = URL.createObjectURL(new Blob([text], { type: 'text/plain' }));
          a.download = 'tradeflow-recovery-codes.txt';
          a.click();
          URL.revokeObjectURL(a.href);
        }}><Download className="size-3.5" /> Download</Button>
      </div>
      <label className="flex items-center gap-2 text-sm">
        <input type="checkbox" checked={saved} onChange={(e) => setSaved(e.target.checked)} /> I have stored these codes somewhere safe
      </label>
      <Button onClick={onDone} disabled={!saved}>Done</Button>
    </div>
  );
}

function ManageMfa({ remaining, enabledAt, onChange }: { remaining: number; enabledAt: string | null; onChange: () => void }) {
  const toast = useToast();
  const { forgetSession } = useAuth();
  const [mode, setMode] = useState<'none' | 'regenerate' | 'disable'>('none');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [codes, setCodes] = useState<string[] | null>(null);

  const reset = () => { setMode('none'); setPassword(''); setCode(''); setError(null); };

  async function submit() {
    setBusy(true); setError(null);
    try {
      if (mode === 'regenerate') {
        setCodes((await mfaApi.regenerateRecoveryCodes(password, code)).recoveryCodes);
        reset();
      } else {
        await mfaApi.disable(password, /^\d{6}$/.test(code) ? { code } : { recoveryCode: code });
        toast('info', 'Two-factor authentication is off. All sessions were signed out.');
        forgetSession('manual');
      }
    } catch (err) {
      setError(message(err));
    } finally {
      setBusy(false);
    }
  }

  if (codes) return <RecoveryCodes codes={codes} onDone={() => { setCodes(null); onChange(); }} />;

  return (
    <div className="space-y-4 text-sm">
      <p className="text-slate-600">Enabled {dateTime(enabledAt)}. <strong>{remaining}</strong> unused recovery code{remaining === 1 ? '' : 's'} left.</p>
      {remaining <= 3 && <Alert tone="warning">You are running low on recovery codes. Generate a new set.</Alert>}
      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" size="sm" onClick={() => setMode('regenerate')}>New recovery codes</Button>
        <Button variant="secondary" size="sm" className="border-rose-200 text-rose-700 hover:bg-rose-50" onClick={() => setMode('disable')}>
          <ShieldOff className="size-3.5" /> Turn off
        </Button>
      </div>
      <ConfirmDialog open={mode !== 'none'} title={mode === 'regenerate' ? 'Replace recovery codes?' : 'Turn off two-factor authentication?'}
        confirmLabel={mode === 'regenerate' ? 'Generate new codes' : 'Turn off and sign out everywhere'}
        variant={mode === 'disable' ? 'danger' : 'primary'} busy={busy} error={error} onConfirm={submit} onCancel={reset}>
        <div className="space-y-3">
          {mode === 'regenerate'
            ? <p>Your current recovery codes stop working.</p>
            : <p>Live trading will be unavailable until you turn it on again. Every session will be signed out.</p>}
          <TextInput label="Password" type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} />
          <TextInput label={mode === 'disable' ? 'Authenticator code or recovery code' : 'Authenticator code'} value={code}
            onChange={(e) => setCode(e.target.value.trim())} autoComplete="one-time-code" />
        </div>
      </ConfirmDialog>
    </div>
  );
}

function SessionsCard() {
  const { forgetSession } = useAuth();
  const toast = useToast();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function signOutEverywhere() {
    setBusy(true); setError(null);
    try {
      await authApi.logoutAll();
      toast('info', 'Signed out of every device');
      forgetSession('manual');
    } catch (err) {
      setError(message(err));
      setBusy(false);
    }
  }
  return (
    <Card title="Sessions">
      <p className="text-sm text-slate-600">
        Sign-ins stay active for up to 24 hours (8 hours without activity). If you think someone else has access,
        sign out everywhere and change your password.
      </p>
      <Button variant="secondary" className="mt-4" onClick={() => setConfirming(true)}><LogOut className="size-4" /> Sign out everywhere</Button>
      <ConfirmDialog open={confirming} title="Sign out of every device?" confirmLabel="Sign out everywhere" variant="danger"
        busy={busy} error={error} onConfirm={signOutEverywhere} onCancel={() => setConfirming(false)}>
        All sessions, including this one, will end immediately.
      </ConfirmDialog>
    </Card>
  );
}
