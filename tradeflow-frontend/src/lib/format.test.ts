import { describe, expect, it } from 'vitest';
import { money, percent, signedMoney, signedPercent, trendClass } from './format';

describe('format', () => {
  it('formats INR with Indian digit grouping', () => {
    expect(money(1212495.76)).toBe('₹12,12,495.76');
    expect(money(null)).toBe('—');
  });

  it('signs P&L values', () => {
    expect(signedMoney(8452.07)).toBe('+₹8,452.07');
    expect(signedMoney(-56)).toBe('−₹56.00');
    expect(signedPercent(0.212496)).toBe('+21.25%');
    expect(signedPercent(-0.024989)).toBe('−2.50%');
    expect(percent(0.378472, 1)).toBe('37.8%');
  });

  it('colours gains green and losses red', () => {
    expect(trendClass(1)).toContain('emerald');
    expect(trendClass(-1)).toContain('rose');
    expect(trendClass(0)).toContain('slate');
  });
});
