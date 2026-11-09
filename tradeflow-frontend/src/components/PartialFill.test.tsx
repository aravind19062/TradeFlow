import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen } from '@testing-library/react';
import type { OrderResponse, OrderStatus } from '../api/types';

vi.mock('../api/endpoints', () => ({ orderApi: { cancel: vi.fn() } }));

const { CancelOrderButton } = await import('./CancelOrderButton');
const { OrderStatusBadge } = await import('./trading');
const { ToastProvider } = await import('./ui');

const order = (status: OrderStatus) => ({ id: 'o1', symbol: 'TCS', side: 'BUY', type: 'LIMIT', quantity: 100, filledQuantity: 40, status } as OrderResponse);
const show = (status: OrderStatus) => render(<ToastProvider><CancelOrderButton order={order(status)} /></ToastProvider>);

describe('partially filled orders', () => {
  afterEach(cleanup);

  it('can still be cancelled while the rest is working', () => {
    show('PARTIALLY_FILLED');
    expect(screen.getByRole('button', { name: /cancel/i })).toBeInTheDocument();
  });

  it.each(['PENDING', 'FILLED', 'CANCELLED', 'EXPIRED', 'REJECTED'] as OrderStatus[])('cannot be cancelled when %s', (status) => {
    show(status);
    expect(screen.queryByRole('button', { name: /cancel/i })).toBeNull();
  });

  it('shows a readable status', () => {
    render(<OrderStatusBadge status="PARTIALLY_FILLED" />);
    expect(screen.getByText('PARTIALLY FILLED')).toHaveAttribute('title', expect.stringContaining('later ticks'));
  });
});
