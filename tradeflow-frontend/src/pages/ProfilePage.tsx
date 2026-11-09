import { useState, type FormEvent, type ReactNode } from 'react';
import { Link } from 'react-router';
import { Landmark, Mail, ShieldCheck, UserRound } from 'lucide-react';
import { ApiError } from '../api/client';
import { mfaApi, profileApi, readinessApi } from '../api/endpoints';
import type { BankAccountView, UserResponse } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Alert, Badge, Button, Card, ErrorState, Modal, PageHeader, SelectInput, Spinner, TextInput, useToast } from '../components/ui';
import { dateTime } from '../lib/format';
import { useApi } from '../lib/useApi';

const message = (e: unknown) => (e instanceof ApiError ? e.message : String(e));
const fieldError = (e: unknown, field: string) => (e instanceof ApiError ? e.fieldError(field) : undefined);
const digits = (value: string, max: number) => value.replace(/\D/g, '').slice(0, max);
const words = (value: string) => value.charAt(0) + value.slice(1).toLowerCase().replace(/_/g, ' ');

/** The signed-in user's personal details, bank account and account status. */
export function ProfilePage() {
  const { user } = useAuth();
  const bank = useApi(() => profileApi.bankAccount(), []);
  const mfa = useApi(() => mfaApi.status(), []);
  const readiness = useApi(() => readinessApi.status(), []);
  if (!user) return <Spinner />;

  return (
    <>
      <PageHeader icon={<UserRound className="size-5" />} tone="teal" title="Profile" subtitle="Your details, bank account and account status." />
      <div className="grid gap-6 lg:grid-cols-2">
        <PersonalDetails key={user.id} user={user} />
        <div className="space-y-6">
          <Card title={<span className="flex items-center gap-2"><Mail className="size-4" /> Sign-in email</span>}>
            <p className="break-all text-sm font-medium text-slate-900" data-testid="profile-email">{user.email}</p>
            <p className="mt-1 text-xs text-slate-500">This is your sign-in identity and can't be changed here.</p>
          </Card>
          <Card title={<span className="flex items-center gap-2"><Landmark className="size-4" /> Bank account</span>}>
            {bank.error ? <ErrorState error={bank.error} onRetry={bank.reload} compact />
              : !bank.data || !mfa.data ? <Spinner />
                : <BankAccount account={bank.data.account} mfaOn={mfa.data.enabled} onChange={bank.reload} />}
          </Card>
          <Card title={<span className="flex items-center gap-2"><ShieldCheck className="size-4" /> Account</span>}>
            <dl className="space-y-2 text-sm">
              <Row label="Member since">{dateTime(user.createdAt)}</Row>
              <Row label="Status">{user.active ? 'Active' : 'Inactive'}</Row>
              <Row label="Role">{words(user.role)}</Row>
              <Row label="Two-factor">{mfa.data ? (mfa.data.enabled ? 'On' : 'Off') : '…'}</Row>
              <Row label="Live-trading readiness">{readiness.data ? words(readiness.data.state) : readiness.error ? 'Unavailable' : '…'}</Row>
            </dl>
            <Link to="/settings/security" className="mt-3 inline-block text-sm font-medium text-indigo-600 hover:underline">Manage security</Link>
          </Card>
        </div>
      </div>
    </>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex justify-between gap-4">
      <dt className="text-slate-500">{label}</dt>
      <dd className="text-right font-medium text-slate-900">{children}</dd>
    </div>
  );
}

function PersonalDetails({ user }: { user: UserResponse }) {
  const { updateUser } = useAuth();
  const toast = useToast();
  const [form, setForm] = useState({
    name: user.name ?? '', dateOfBirth: user.dateOfBirth ?? '', gender: user.gender ?? '', mobileNumber: user.mobileNumber ?? '',
    occupation: user.occupation ?? '', maritalStatus: user.maritalStatus ?? '', incomeRange: user.incomeRange ?? '',
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const set = (key: keyof typeof form) => (e: { target: { value: string } }) => setForm((f) => ({ ...f, [key]: e.target.value }));

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      updateUser(await profileApi.update({
        name: form.name, dateOfBirth: form.dateOfBirth || null, gender: form.gender || null, mobileNumber: form.mobileNumber || null,
        occupation: form.occupation || null, maritalStatus: form.maritalStatus || null, incomeRange: form.incomeRange || null,
      }));
      toast('success', 'Profile saved');
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  const fe = (f: string) => fieldError(error, f);
  return (
    <Card title="Personal details">
      <form onSubmit={submit} className="grid gap-3 sm:grid-cols-2" data-testid="profile-form">
        <div className="sm:col-span-2"><TextInput label="Full name" value={form.name} onChange={set('name')} maxLength={100} required error={fe('name')} /></div>
        <TextInput label="Date of birth" type="date" value={form.dateOfBirth} onChange={set('dateOfBirth')} error={fe('dateOfBirth')} />
        <TextInput label="Mobile number" inputMode="tel" autoComplete="tel" value={form.mobileNumber} onChange={set('mobileNumber')} placeholder="+919876543210" error={fe('mobileNumber')} />
        <SelectInput label="Gender" value={form.gender} onChange={set('gender')} error={fe('gender')}>
          <option value="">Prefer not to say</option>
          <option>Female</option><option>Male</option><option>Other</option>
        </SelectInput>
        <TextInput label="Marital status" value={form.maritalStatus} onChange={set('maritalStatus')} maxLength={20} error={fe('maritalStatus')} />
        <TextInput label="Occupation" value={form.occupation} onChange={set('occupation')} maxLength={100} error={fe('occupation')} />
        <TextInput label="Income range" value={form.incomeRange} onChange={set('incomeRange')} maxLength={50} error={fe('incomeRange')} />
        {error != null && !(error instanceof ApiError && error.fieldErrors.length > 0) && <div className="sm:col-span-2"><Alert tone="error">{message(error)}</Alert></div>}
        <div className="flex justify-end sm:col-span-2"><Button type="submit" loading={busy}>Save changes</Button></div>
      </form>
    </Card>
  );
}

function BankAccount({ account, mfaOn, onChange }: { account: BankAccountView | null; mfaOn: boolean; onChange: () => void }) {
  const toast = useToast();
  const [dialog, setDialog] = useState<'edit' | 'remove' | null>(null);
  const close = () => setDialog(null);
  const done = (text: string) => { close(); onChange(); toast('success', text); };

  return (
    <div className="text-sm">
      {account ? (
        <dl className="space-y-2" data-testid="bank-account">
          <Row label="Account holder">{account.accountHolderName}</Row>
          <Row label="Bank">{account.bankName}</Row>
          <Row label="Account number"><span data-testid="bank-masked-number">{account.maskedAccountNumber}</span></Row>
          <Row label="IFSC">{account.ifsc}</Row>
          <Row label="Type"><Badge className="bg-slate-100 text-slate-600 ring-1 ring-slate-200">{words(account.accountType)}</Badge></Row>
          <Row label="Last updated">{dateTime(account.updatedAt)}</Row>
        </dl>
      ) : <p className="text-slate-600">No bank account saved.</p>}
      {mfaOn ? (
        <div className="mt-4 flex justify-end gap-2">
          {account && <Button variant="secondary" size="sm" onClick={() => setDialog('remove')}>Remove</Button>}
          <Button size="sm" onClick={() => setDialog('edit')}>{account ? 'Replace' : 'Add bank account'}</Button>
        </div>
      ) : (
        <div className="mt-3">
          <Alert tone="info">Turn on two-factor authentication in <Link to="/settings/security" className="font-medium underline">Security</Link> to add or change bank details.</Alert>
        </div>
      )}
      <p className="mt-3 text-xs text-slate-500">Stored encrypted, and only the last 4 digits are ever shown. TradeFlow doesn't use it to move money.</p>
      <Modal open={dialog === 'edit'} onClose={close} title={account ? 'Replace bank account' : 'Add bank account'}>
        <BankForm initial={account} onSaved={() => done('Bank account saved')} onCancel={close} />
      </Modal>
      <Modal open={dialog === 'remove'} onClose={close} title="Remove bank account">
        <RemoveForm onRemoved={() => done('Bank account removed')} onCancel={close} />
      </Modal>
    </div>
  );
}

/** Mounted only while its dialog is open, so typed details and the password don't linger in state. */
function BankForm({ initial, onSaved, onCancel }: { initial: BankAccountView | null; onSaved: () => void; onCancel: () => void }) {
  const [form, setForm] = useState({
    accountHolderName: initial?.accountHolderName ?? '', bankName: initial?.bankName ?? '', accountNumber: '', confirmNumber: '',
    ifsc: initial?.ifsc ?? '', accountType: initial?.accountType ?? 'SAVINGS', password: '', code: '',
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const update = (key: keyof typeof form, value: string) => setForm((f) => ({ ...f, [key]: value }));
  const mismatch = form.confirmNumber !== '' && form.confirmNumber !== form.accountNumber;
  const ready = form.accountNumber !== '' && form.accountNumber === form.confirmNumber && form.password !== '' && form.code.length === 6;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      const { confirmNumber: _confirm, ...body } = form;
      await profileApi.saveBankAccount(body);
      onSaved();
    } catch (err) {
      setError(err);
      setForm((f) => ({ ...f, password: '', code: '' }));
    } finally {
      setBusy(false);
    }
  }

  const fe = (f: string) => fieldError(error, f);
  return (
    <form onSubmit={submit} className="space-y-3" data-testid="bank-form" autoComplete="off">
      <TextInput label="Account holder name" value={form.accountHolderName} onChange={(e) => update('accountHolderName', e.target.value)} maxLength={100} required error={fe('accountHolderName')} />
      <TextInput label="Bank name" value={form.bankName} onChange={(e) => update('bankName', e.target.value)} maxLength={100} required error={fe('bankName')} />
      <TextInput label="Account number" inputMode="numeric" autoComplete="off" value={form.accountNumber} onChange={(e) => update('accountNumber', digits(e.target.value, 18))} required error={fe('accountNumber')} />
      <TextInput label="Confirm account number" inputMode="numeric" autoComplete="off" value={form.confirmNumber} onChange={(e) => update('confirmNumber', digits(e.target.value, 18))} required error={mismatch ? "Account numbers don't match" : undefined} />
      <div className="grid gap-3 sm:grid-cols-2">
        <TextInput label="IFSC" value={form.ifsc} onChange={(e) => update('ifsc', e.target.value.toUpperCase())} maxLength={11} required placeholder="HDFC0001234" error={fe('ifsc')} />
        <SelectInput label="Account type" value={form.accountType} onChange={(e) => update('accountType', e.target.value)}>
          <option value="SAVINGS">Savings</option><option value="CURRENT">Current</option>
        </SelectInput>
      </div>
      <p className="border-t border-slate-100 pt-3 text-slate-600">Confirm it's you to save these details.</p>
      <ConfirmFields password={form.password} code={form.code} onPassword={(v) => update('password', v)} onCode={(v) => update('code', v)} />
      {error != null && !(error instanceof ApiError && error.fieldErrors.length > 0) && <Alert tone="error">{message(error)}</Alert>}
      <div className="flex justify-end gap-2 pt-1">
        <Button type="button" variant="secondary" onClick={onCancel} disabled={busy}>Cancel</Button>
        <Button type="submit" loading={busy} disabled={!ready}>Save</Button>
      </div>
    </form>
  );
}

function RemoveForm({ onRemoved, onCancel }: { onRemoved: () => void; onCancel: () => void }) {
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true); setError(null);
    try {
      await profileApi.removeBankAccount(password, code);
      onRemoved();
    } catch (err) {
      setError(message(err));
      setPassword(''); setCode('');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={submit} className="space-y-3" data-testid="bank-remove-form">
      <p className="text-slate-600">Your saved bank details will be permanently deleted.</p>
      <ConfirmFields password={password} code={code} onPassword={setPassword} onCode={setCode} />
      {error && <Alert tone="error">{error}</Alert>}
      <div className="flex justify-end gap-2 pt-1">
        <Button type="button" variant="secondary" onClick={onCancel} disabled={busy}>Cancel</Button>
        <Button type="submit" variant="danger" loading={busy} disabled={!password || code.length !== 6}>Remove</Button>
      </div>
    </form>
  );
}

function ConfirmFields({ password, code, onPassword, onCode }: {
  password: string; code: string; onPassword: (v: string) => void; onCode: (v: string) => void;
}) {
  return (
    <>
      <TextInput label="Password" type="password" autoComplete="current-password" value={password} onChange={(e) => onPassword(e.target.value)} required />
      <TextInput label="Authenticator code" inputMode="numeric" autoComplete="one-time-code" value={code} onChange={(e) => onCode(digits(e.target.value, 6))} required />
    </>
  );
}
