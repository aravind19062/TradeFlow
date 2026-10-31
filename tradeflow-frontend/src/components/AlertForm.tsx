import { useState, type FormEvent } from 'react';
import { ApiError } from '../api/client';
import { alertApi } from '../api/endpoints';
import type { AlertDirection } from '../api/types';
import { invalidate } from '../lib/useApi';
import { money } from '../lib/format';
import { Button, Segmented, TextInput, useToast } from './ui';

/** POST /api/alerts. Alerts are evaluated by market-data-service on every simulated tick. */
export function AlertForm({ symbol, currentPrice }: { symbol: string; currentPrice?: number }) {
  const toast = useToast();
  const [direction, setDirection] = useState<AlertDirection>('ABOVE');
  const [threshold, setThreshold] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!/^\d+(\.\d{1,2})?$/.test(threshold) || Number(threshold) < 0.01) {
      setError('Enter a price of at least 0.01 with up to 2 decimals');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      await alertApi.create({ symbol, direction, threshold: Number(threshold), ...(note.trim() ? { note: note.trim() } : {}) });
      invalidate('alerts');
      toast('success', `Alert set: ${symbol} ${direction === 'ABOVE' ? '≥' : '≤'} ${money(Number(threshold))}`);
      setThreshold('');
      setNote('');
    } catch (err) {
      setError(err instanceof ApiError ? (err.fieldErrors[0]?.message ?? err.message) : String(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-3" noValidate aria-label={`New price alert for ${symbol}`}>
      <Segmented label="Direction" value={direction} onChange={setDirection} options={[
        { value: 'ABOVE', label: 'Rises to' }, { value: 'BELOW', label: 'Falls to' },
      ]} />
      <TextInput label="Price (₹)" inputMode="decimal" value={threshold} onChange={(e) => setThreshold(e.target.value.trim())}
        placeholder={currentPrice?.toFixed(2)} error={error ?? undefined} />
      <TextInput label="Note (optional)" value={note} onChange={(e) => setNote(e.target.value)} maxLength={200} />
      <Button type="submit" variant="secondary" className="w-full" loading={busy}>Create alert</Button>
    </form>
  );
}
