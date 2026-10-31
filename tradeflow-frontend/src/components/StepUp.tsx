import { createContext, useCallback, useContext, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { ShieldCheck } from 'lucide-react';
import { ApiError } from '../api/client';
import { authApi } from '../api/endpoints';
import { currentStepUp, sessionFromToken, setStepUp } from '../auth/session';
import { Alert, Button, Modal, TextInput } from './ui';

type Runner = <T>(action: (stepUpToken: string) => Promise<T>) => Promise<T>;

const StepUpContext = createContext<Runner | null>(null);

/** Cancelled by the user; callers can ignore it. */
export class StepUpCancelled extends Error {
  constructor() { super('Confirmation cancelled'); this.name = 'StepUpCancelled'; }
}

/**
 * Live actions need a fresh password + authenticator code (a 10-minute step-up token from
 * user-service, kept in memory only). `run` reuses a still-valid token, otherwise asks; if the
 * live service says the token is no longer good enough it asks once more.
 */
export function StepUpProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pending = useRef<{ resolve: (t: string) => void; reject: (e: Error) => void } | null>(null);

  const ask = useCallback(() => new Promise<string>((resolve, reject) => {
    pending.current = { resolve, reject };
    setPassword(''); setCode(''); setError(null); setOpen(true);
  }), []);

  const run: Runner = useCallback(async (action) => {
    const token = currentStepUp()?.token ?? await ask();
    try {
      return await action(token);
    } catch (e) {
      if (e instanceof ApiError && e.code === 'STEP_UP_REQUIRED') {
        setStepUp(null);
        return action(await ask());
      }
      throw e;
    }
  }, [ask]);

  function cancel() {
    setOpen(false);
    pending.current?.reject(new StepUpCancelled());
    pending.current = null;
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      const res = await authApi.stepUp(password, code.trim());
      const session = sessionFromToken(res.stepUpToken);
      if (!session) throw new Error('Unexpected step-up response');
      setStepUp(session);
      setOpen(false);
      pending.current?.resolve(session.token);
      pending.current = null;
    } catch (err) {
      setError(err instanceof ApiError ? err.message : String(err));
    } finally {
      setPassword('');
      setBusy(false);
    }
  }

  return (
    <StepUpContext.Provider value={run}>
      {children}
      <Modal open={open} onClose={busy ? () => {} : cancel} title="Confirm it's you">
        <form onSubmit={submit} className="space-y-3" data-testid="step-up-form">
          <p className="flex gap-2"><ShieldCheck className="size-5 shrink-0 text-rose-600" />
            Live trading uses real money. Enter your password and the current code from your authenticator app.
            The confirmation lasts 10 minutes.</p>
          <TextInput label="Password" type="password" autoComplete="current-password" value={password}
            onChange={(e) => setPassword(e.target.value)} required />
          <TextInput label="Authenticator code" inputMode="numeric" autoComplete="one-time-code" value={code}
            onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))} required />
          {error && <Alert tone="error">{error}</Alert>}
          <div className="flex justify-end gap-2 pt-1">
            <Button type="button" variant="secondary" onClick={cancel} disabled={busy}>Cancel</Button>
            <Button type="submit" variant="danger" loading={busy} disabled={!password || code.length !== 6}>Confirm</Button>
          </div>
        </form>
      </Modal>
    </StepUpContext.Provider>
  );
}

export function useStepUp(): Runner {
  const run = useContext(StepUpContext);
  if (!run) throw new Error('useStepUp must be used inside StepUpProvider');
  return run;
}
