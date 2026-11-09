import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import type { BankAccountView, UserResponse } from '../api/types';

const user: UserResponse = {
  id: 1, name: 'Asha Rao', email: 'asha@test.dev', role: 'USER', dateOfBirth: '1995-02-03', gender: null,
  mobileNumber: '+919876543210', occupation: null, maritalStatus: null, incomeRange: null, active: true, createdAt: '2026-01-01T10:00:00',
};
const saved: BankAccountView = {
  accountHolderName: 'Asha Rao', bankName: 'HDFC Bank', maskedAccountNumber: '••••••9012', ifsc: 'HDFC0001234',
  accountType: 'SAVINGS', updatedAt: '2026-11-09T10:00:00Z',
};

const api = vi.hoisted(() => ({
  bankAccount: vi.fn(), saveBankAccount: vi.fn(), removeBankAccount: vi.fn(), update: vi.fn(), status: vi.fn(), readiness: vi.fn(),
}));
const updateUser = vi.hoisted(() => vi.fn());

vi.mock('../api/endpoints', () => ({
  profileApi: { bankAccount: api.bankAccount, saveBankAccount: api.saveBankAccount, removeBankAccount: api.removeBankAccount, update: api.update },
  mfaApi: { status: api.status },
  readinessApi: { status: api.readiness },
}));
vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ user, updateUser }) }));
vi.mock('../components/ui', async (original) => ({
  ...(await original<typeof import('../components/ui')>()),
  useToast: () => () => undefined,
}));

const { ProfilePage } = await import('./ProfilePage');

function show() {
  render(<MemoryRouter><ProfilePage /></MemoryRouter>);
}
const type = (label: string | RegExp, value: string) => fireEvent.change(screen.getByLabelText(label), { target: { value } });

describe('ProfilePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.bankAccount.mockResolvedValue({ account: null });
    api.status.mockResolvedValue({ enabled: true, enabledAt: null, recoveryCodesRemaining: 8, enrollmentPending: false });
    api.readiness.mockResolvedValue({ state: 'NOT_STARTED' });
  });
  afterEach(cleanup);

  it('shows the email read-only and saves personal details', async () => {
    api.update.mockResolvedValue({ ...user, name: 'Asha R' });
    show();
    expect(screen.getByTestId('profile-email')).toHaveTextContent('asha@test.dev');
    type('Full name', 'Asha R');
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(updateUser).toHaveBeenCalledWith(expect.objectContaining({ name: 'Asha R' })));
    expect(api.update).toHaveBeenCalledWith(expect.not.objectContaining({ email: expect.anything() }));
  });

  it('shows only the masked number', async () => {
    api.bankAccount.mockResolvedValue({ account: saved });
    show();
    expect(await screen.findByTestId('bank-masked-number')).toHaveTextContent('••••••9012');
  });

  it('asks for two-factor before bank details can be added', async () => {
    api.status.mockResolvedValue({ enabled: false, enabledAt: null, recoveryCodesRemaining: 0, enrollmentPending: false });
    show();
    expect(await screen.findByText(/to add or change bank details/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Add bank account' })).not.toBeInTheDocument();
  });

  it('needs matching account numbers, then sends password and code without the confirmation field', async () => {
    api.saveBankAccount.mockResolvedValue(saved);
    show();
    fireEvent.click(await screen.findByRole('button', { name: 'Add bank account' }));
    type('Account holder name', 'Asha Rao');
    type('Bank name', 'HDFC Bank');
    type('Account number', '123456789012');
    type('Confirm account number', '123456789000');
    type('IFSC', 'hdfc0001234');
    type('Password', 'Secret#123');
    type('Authenticator code', '123456');
    expect(screen.getByText("Account numbers don't match")).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();

    type('Confirm account number', '123456789012');
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(api.saveBankAccount).toHaveBeenCalledTimes(1));
    const body = api.saveBankAccount.mock.calls[0][0];
    expect(body).toMatchObject({ accountNumber: '123456789012', ifsc: 'HDFC0001234', password: 'Secret#123', code: '123456' });
    expect(body).not.toHaveProperty('confirmNumber');
  });
});
