import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';

const invalidate = vi.hoisted(() => vi.fn());
vi.mock('./useApi', () => ({ invalidate }));
const { useMarketStream } = await import('./useMarketStream');

class FakeSource {
  static last: FakeSource;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  closed = false;
  listeners: Record<string, (e: MessageEvent<string>) => void> = {};
  constructor(public url: string) { FakeSource.last = this; }
  addEventListener(name: string, fn: (e: MessageEvent<string>) => void) { this.listeners[name] = fn; }
  close() { this.closed = true; }
  tick(tick: number) { this.listeners.tick({ data: JSON.stringify({ tick }) } as MessageEvent<string>); }
}

describe('useMarketStream', () => {
  beforeEach(() => { vi.clearAllMocks(); vi.stubGlobal('EventSource', FakeSource); });
  afterEach(() => vi.unstubAllGlobals());

  it('refreshes on a new tick but not on the first or a repeated one', () => {
    renderHook(() => useMarketStream());
    act(() => FakeSource.last.tick(5));
    expect(invalidate).not.toHaveBeenCalled();
    act(() => FakeSource.last.tick(5));
    expect(invalidate).not.toHaveBeenCalled();
    act(() => FakeSource.last.tick(6));
    expect(invalidate).toHaveBeenCalledWith('market', 'portfolio');
  });

  it('reports connection state and closes on unmount', () => {
    const { result, unmount } = renderHook(() => useMarketStream());
    expect(result.current).toBe(false);
    act(() => FakeSource.last.onopen?.());
    expect(result.current).toBe(true);
    act(() => FakeSource.last.onerror?.());
    expect(result.current).toBe(false);
    unmount();
    expect(FakeSource.last.closed).toBe(true);
  });
});
