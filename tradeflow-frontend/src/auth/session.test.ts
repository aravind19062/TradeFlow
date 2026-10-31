import { afterEach, describe, expect, it } from 'vitest';
import {
  clearSession, currentStepUp, currentToken, decodeJwt, isExpired, sessionFromToken, setSession, setStepUp,
} from './session';

function jwt(claims: object): string {
  // Like the real token: base64url over the UTF-8 bytes of the JSON.
  const b64 = (o: object) => btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(o))))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64({ alg: 'RS256' })}.${b64(claims)}.signature`;
}

const nowSec = () => Math.floor(Date.now() / 1000);

afterEach(() => clearSession());

describe('session', () => {
  it('decodes the claims the user-service puts in the token', () => {
    const token = jwt({ sub: '42', email: 'a@b.dev', name: 'Zoë', roles: ['USER'], amr: ['pwd', 'otp'], exp: nowSec() + 900 });
    expect(decodeJwt(token)).toMatchObject({ sub: '42', email: 'a@b.dev', name: 'Zoë', roles: ['USER'], amr: ['pwd', 'otp'] });
  });

  it('rejects malformed tokens', () => {
    expect(decodeJwt('not-a-jwt')).toBeNull();
    expect(decodeJwt('a.b.c')).toBeNull();
    expect(decodeJwt(jwt({ sub: '1' }))).toBeNull(); // no exp
  });

  it('takes the expiry from exp and treats near-expiry as expired', () => {
    const s = sessionFromToken(jwt({ sub: '1', exp: nowSec() + 900 }))!;
    expect(s.expiresAt).toBe((nowSec() + 900) * 1000);
    expect(isExpired(s)).toBe(false);
    expect(isExpired({ token: 't', expiresAt: Date.now() + 10_000 })).toBe(true);
  });

  it('keeps tokens in memory only, never in web storage', () => {
    setSession(sessionFromToken(jwt({ sub: '1', exp: nowSec() + 900 })));
    expect(currentToken()).not.toBeNull();
    expect(sessionStorage.length).toBe(0);
    expect(localStorage.length).toBe(0);
  });

  it('never returns an expired token', () => {
    setSession({ token: 'old', expiresAt: Date.now() - 1 });
    expect(currentToken()).toBeNull();
  });

  it('drops the live step-up token with the session', () => {
    setSession(sessionFromToken(jwt({ sub: '1', exp: nowSec() + 900 })));
    setStepUp(sessionFromToken(jwt({ sub: '1', aud: 'tradeflow-live', exp: nowSec() + 600 })));
    expect(currentStepUp()).not.toBeNull();
    clearSession();
    expect(currentStepUp()).toBeNull();
  });
});
