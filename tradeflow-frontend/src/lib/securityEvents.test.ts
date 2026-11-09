import { describe, expect, it } from 'vitest';
import { deviceName, isWarningEvent } from './securityEvents';

describe('deviceName', () => {
  it('names common browsers and systems', () => {
    expect(deviceName('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 Chrome/140.0 Safari/537.36')).toBe('Chrome on macOS');
    expect(deviceName('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 Version/18.0 Mobile Safari/604.1')).toBe('Safari on iOS');
    expect(deviceName('Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 Chrome/140.0 Safari/537.36 Edg/140.0')).toBe('Edge on Windows');
  });
  it('handles unknown clients', () => {
    expect(deviceName(null)).toBe('Unknown device');
    expect(deviceName('curl/8.0')).toBe('Unknown device');
  });
});

describe('isWarningEvent', () => {
  it('flags failures and token reuse only', () => {
    expect(isWarningEvent('LOGIN_FAILED')).toBe(true);
    expect(isWarningEvent('REFRESH_REUSE_DETECTED')).toBe(true);
    expect(isWarningEvent('LOGIN_SUCCEEDED')).toBe(false);
  });
});
