import { useEffect, useState } from 'react';
import { invalidate } from './useApi';

/**
 * Refreshes market and portfolio data the moment the simulator produces new prices. EventSource
 * reconnects by itself; while it is down, the pages' slower polling keeps them up to date.
 */
export function useMarketStream(): boolean {
  const [connected, setConnected] = useState(false);
  useEffect(() => {
    if (typeof EventSource === 'undefined') return;
    const source = new EventSource('/api/demo/market/stream');
    let lastTick: number | null = null;
    source.onopen = () => setConnected(true);
    source.onerror = () => setConnected(false);
    source.addEventListener('tick', (e) => {
      setConnected(true);
      const tick = (JSON.parse((e as MessageEvent<string>).data) as { tick: number }).tick;
      // The first event only says where the stream starts; the page has just loaded those prices.
      if (lastTick !== null && tick !== lastTick) invalidate('market', 'portfolio');
      lastTick = tick;
    });
    return () => source.close();
  }, []);
  return connected;
}
