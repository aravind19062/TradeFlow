import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';

vi.mock('../components/StepUp', () => ({
  useStepUp: () => async () => undefined,
  StepUpCancelled: class extends Error {},
}));

const { BrokerCallbackPage } = await import('./LivePages');

function renderAt(url: string) {
  const router = createMemoryRouter([{ path: '/live/broker/callback', element: <BrokerCallbackPage /> }], { initialEntries: [url] });
  render(<RouterProvider router={router} />);
  return router;
}

describe('BrokerCallbackPage (Fyers redirect)', () => {
  afterEach(() => {
    cleanup();
    window.history.replaceState(null, '', '/');
  });

  it('accepts auth_code with the state TradeFlow issued and offers to finish linking', () => {
    renderAt('/live/broker/callback?auth_code=aaa.bbb.ccc&state=s-123');
    expect(screen.getByRole('button', { name: 'Confirm and link' })).toBeInTheDocument();
  });

  it('removes the one-time code from the address and can still finish linking', async () => {
    const url = '/live/broker/callback?auth_code=aaa.bbb.ccc&state=s-123';
    window.history.replaceState(null, '', url);
    const router = renderAt(url);
    await waitFor(() => expect(router.state.location.search).toBe(''));
    expect(screen.getByRole('button', { name: 'Confirm and link' })).toBeInTheDocument();
  });

  it('does not offer linking when the broker does not return the state', () => {
    renderAt('/live/broker/callback?auth_code=aaa.bbb.ccc');
    expect(screen.getByText(/opened without a broker authorisation/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirm and link' })).not.toBeInTheDocument();
  });

  it('shows the broker message when the user declines', () => {
    renderAt('/live/broker/callback?s=error&message=Cancelled&state=s-123');
    expect(screen.getByText(/did not authorise the link: Cancelled/)).toBeInTheDocument();
  });
});
