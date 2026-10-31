import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react';
import { ApiError } from '../api/client';

// ---- Cross-page refresh: after a mutation (order placed, alert created, ...) the caller
// bumps a topic and every mounted query that depends on it refetches.
export type Topic = 'portfolio' | 'orders' | 'watchlist' | 'alerts' | 'market';

const versions: Record<Topic, number> = { portfolio: 0, orders: 0, watchlist: 0, alerts: 0, market: 0 };
const listeners = new Set<() => void>();

export function invalidate(...topics: Topic[]): void {
  for (const t of topics) versions[t] += 1;
  listeners.forEach((l) => l());
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function useTopicVersion(topics: readonly Topic[]): string {
  return useSyncExternalStore(subscribe, () => topics.map((t) => versions[t]).join('.'));
}

export interface ApiState<T> {
  data: T | undefined;
  error: ApiError | null;
  /** True only while there is no data yet; background refreshes keep showing the old data. */
  loading: boolean;
  refreshing: boolean;
  reload: () => void;
}

interface Options {
  /** Refetch when any of these topics is invalidated. */
  topics?: readonly Topic[];
  /** Poll in the background (paused while the tab is hidden). */
  intervalMs?: number;
  enabled?: boolean;
}

/** Minimal data-fetching hook: loading / error / data, refetch on deps, topics and interval. */
export function useApi<T>(fetcher: () => Promise<T>, deps: readonly unknown[], options: Options = {}): ApiState<T> {
  const { topics = [], intervalMs, enabled = true } = options;
  const [data, setData] = useState<T>();
  const [error, setError] = useState<ApiError | null>(null);
  const [inFlight, setInFlight] = useState(false);
  const [manual, setManual] = useState(0);
  const topicVersion = useTopicVersion(topics);
  const fetcherRef = useRef(fetcher);
  fetcherRef.current = fetcher;
  const requestId = useRef(0);

  const run = useCallback(() => {
    const id = ++requestId.current;
    setInFlight(true);
    fetcherRef.current()
      .then((result) => {
        if (id !== requestId.current) return;
        setData(result);
        setError(null);
      })
      .catch((e: unknown) => {
        if (id !== requestId.current) return;
        setError(e instanceof ApiError ? e : new ApiError(0, 'CLIENT_ERROR', String(e)));
      })
      .finally(() => { if (id === requestId.current) setInFlight(false); });
  }, []);

  // Changing the query itself (deps) discards the previous result.
  useEffect(() => {
    setData(undefined);
    setError(null);
  }, deps);

  useEffect(() => {
    if (enabled) run();
    return () => { requestId.current++; };
  }, [...deps, topicVersion, manual, enabled]);

  useEffect(() => {
    if (!enabled || !intervalMs) return;
    const timer = window.setInterval(() => { if (!document.hidden) run(); }, intervalMs);
    return () => window.clearInterval(timer);
  }, [enabled, intervalMs, run]);

  return {
    data,
    error,
    loading: enabled && inFlight && data === undefined,
    refreshing: inFlight && data !== undefined,
    reload: useCallback(() => setManual((m) => m + 1), []),
  };
}
