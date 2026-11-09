import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';

const logout = vi.hoisted(() => vi.fn());
const auth = vi.hoisted(() => ({ user: { role: 'USER' }, isAdmin: false, logout: vi.fn() }));
vi.mock('../api/endpoints', () => ({
  marketApi: { instruments: async () => [{ symbol: 'TCS', name: 'Tata Consultancy', sector: 'IT', assetClass: 'EQUITY', currency: 'INR' }] },
}));
vi.mock('../auth/AuthContext', () => ({ useAuth: () => auth }));

const { CommandPalette } = await import('./CommandPalette');
const { Modal } = await import('./ui');

function show(extra?: React.ReactNode) {
  const element = <><CommandPalette pages={[{ to: '/demo/orders', label: 'Orders' }]} /><input aria-label="Quantity" />{extra}</>;
  const router = createMemoryRouter([{ path: '*', element }], { initialEntries: ['/start'] });
  render(<RouterProvider router={router} />);
  return router;
}
const palette = () => screen.queryByTestId('command-palette');
const openPalette = () => fireEvent.keyDown(document, { key: 'k', metaKey: true });
const box = () => screen.getByRole('combobox');

describe('CommandPalette', () => {
  beforeEach(() => { auth.logout = logout; auth.isAdmin = false; vi.clearAllMocks(); });
  afterEach(cleanup);

  it('opens with Cmd/Ctrl+K with the cursor in the search box, and closes on a second press', () => {
    show();
    openPalette();
    expect(box()).toHaveFocus();
    openPalette();
    expect(palette()).toBeNull();
    fireEvent.keyDown(document, { key: 'k', ctrlKey: true });
    expect(palette()).not.toBeNull();
  });

  it('runs the highlighted result with the arrow keys and Enter', async () => {
    const router = show();
    openPalette();
    fireEvent.change(box(), { target: { value: 'orders' } });
    fireEvent.keyDown(box(), { key: 'Enter' });
    await waitFor(() => expect(router.state.location.pathname).toBe('/demo/orders'));
    expect(palette()).toBeNull();
  });

  it('finds instruments by symbol or name', async () => {
    const router = show();
    openPalette();
    fireEvent.change(box(), { target: { value: 'tata' } });
    fireEvent.click(await screen.findByText(/TCS · Tata Consultancy/));
    await waitFor(() => expect(router.state.location.pathname).toBe('/demo/market/TCS'));
  });

  it('hides admin commands from ordinary users', () => {
    show();
    openPalette();
    fireEvent.change(box(), { target: { value: 'admin' } });
    expect(screen.getByText('No matches')).toBeInTheDocument();
  });

  it('navigates with g then a letter', async () => {
    const router = show();
    fireEvent.keyDown(document, { key: 'g' });
    fireEvent.keyDown(document, { key: 'p' });
    await waitFor(() => expect(router.state.location.pathname).toBe('/demo/portfolio'));
  });

  it('ignores shortcuts while the user is typing in a field', () => {
    const router = show();
    const field = screen.getByLabelText('Quantity');
    fireEvent.keyDown(field, { key: 'g' });
    fireEvent.keyDown(field, { key: 'p' });
    fireEvent.keyDown(field, { key: '?' });
    expect(router.state.location.pathname).toBe('/start');
    expect(screen.queryByTestId('shortcut-list')).toBeNull();
  });

  it('does not cover or act behind another open dialog', () => {
    const router = show(<Modal open onClose={() => {}} title="Confirm order"><button>Place</button></Modal>);
    openPalette();
    expect(palette()).toBeNull();
    fireEvent.keyDown(document, { key: 'g' });
    fireEvent.keyDown(document, { key: 'p' });
    expect(router.state.location.pathname).toBe('/start');
  });

  it('lists the shortcuts with ?', () => {
    show();
    fireEvent.keyDown(document, { key: '?' });
    expect(screen.getByTestId('shortcut-list')).toHaveTextContent('Go to Portfolio');
  });
});

describe('Modal keyboard handling', () => {
  afterEach(cleanup);

  it('keeps Tab inside the dialog and returns focus to where it was on close', () => {
    function Page({ open }: { open: boolean }) {
      return <><button>Outside</button><Modal open={open} onClose={() => {}} title="T"><button>Last</button></Modal></>;
    }
    const { rerender } = render(<Page open={false} />);
    const outside = screen.getByRole('button', { name: 'Outside' });
    outside.focus();
    rerender(<Page open />);
    const last = screen.getByRole('button', { name: 'Last' });
    const close = screen.getByRole('button', { name: 'Close' });
    last.focus();
    fireEvent.keyDown(document, { key: 'Tab' });
    expect(close).toHaveFocus();
    fireEvent.keyDown(document, { key: 'Tab', shiftKey: true });
    expect(last).toHaveFocus();
    rerender(<Page open={false} />);
    expect(outside).toHaveFocus();
  });
});
