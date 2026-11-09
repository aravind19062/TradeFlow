import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { clearLiveAcknowledgements } from '../auth/session';

vi.mock('../auth/AuthContext', () => ({ useAuth: () => ({ user: { id: 7, name: 'Asha', email: 'a@x.dev', role: 'USER' } }) }));

const { LiveArea } = await import('./environment');

function renderLive() {
  const router = createMemoryRouter([
    { path: '/demo', element: <h1>Demo home</h1> },
    { path: '/live', element: <LiveArea />, children: [{ index: true, element: <h1>Live page</h1> }] },
  ], { initialEntries: ['/live'] });
  render(<RouterProvider router={router} />);
  return router;
}

describe('LiveArea warning', () => {
  beforeEach(() => sessionStorage.clear());
  afterEach(cleanup);

  it('shows the real-money warning before any live content', () => {
    renderLive();
    expect(screen.getByRole('dialog', { name: "You're entering live trading" })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Live page' })).not.toBeInTheDocument();
  });

  it('Continue shows the page with a persistent LIVE marker, and is remembered for the session', async () => {
    renderLive();
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.getByRole('heading', { name: 'Live page' })).toBeInTheDocument();
    expect(screen.getByTestId('live-banner')).toHaveTextContent('real money');
    cleanup();
    renderLive();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('signing out forgets the acknowledgement', async () => {
    renderLive();
    await userEvent.click(screen.getByRole('button', { name: 'Continue' }));
    cleanup();
    clearLiveAcknowledgements();
    renderLive();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  it('Back to demo leaves the live area without acknowledging', async () => {
    const router = renderLive();
    await userEvent.click(screen.getByRole('button', { name: 'Back to demo' }));
    expect(router.state.location.pathname).toBe('/demo');
    expect(sessionStorage.length).toBe(0);
  });
});
