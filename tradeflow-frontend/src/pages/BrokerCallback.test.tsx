import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
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

describe('BrokerCallbackPage (Angel One redirect)', () => {
  afterEach(cleanup);

  it('accepts auth_token with the state TradeFlow issued and offers to finish linking', () => {
    renderAt('/live/broker/callback?auth_token=aaa.bbb.ccc&state=s-123');
    expect(screen.getByRole('button', { name: 'Confirm and link' })).toBeInTheDocument();
  });

  it('stops for safety when the broker does not return the state', () => {
    renderAt('/live/broker/callback?auth_token=aaa.bbb.ccc');
    expect(screen.getByText('Linking stopped for your safety')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Confirm and link' })).not.toBeInTheDocument();
  });

  it('explains a page opened without any broker authorisation', () => {
    renderAt('/live/broker/callback');
    expect(screen.getByText(/opened without a broker authorisation/)).toBeInTheDocument();
  });
});
