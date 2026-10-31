import { afterEach, describe, expect, it } from 'vitest';
import { currentToken, decodeJwt, isExpired, loadSession, saveSession, sessionFromToken } from './session';

function jwt(claims: object): string {
  // Like the real token: base64url over the UTF-8 bytes of the JSON.
  const b64 = (o: object) => btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(o))))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
  return `${b64({ alg: 'HS256' })}.${b64(claims)}.signature`;
}

const nowSec = () => Math.floor(Date.now() / 1000);

afterEach(() => sessionStorage.clear());

describe('session', () => {
  it('decodes the claims the user-service puts in the token', () => {
    const token = jwt({ sub: '42', email: 'a@b.dev', name: 'Zoë', roles: ['USER'], exp: nowSec() + 3600 });
    expect(decodeJwt(token)).toMatchObject({ sub: '42', email: 'a@b.dev', name: 'Zoë', roles: ['USER'] });
  });

  it('rejects malformed tokens', () => {
    expect(decodeJwt('not-a-jwt')).toBeNull();
    expect(decodeJwt('a.b.c')).toBeNull();
    expect(decodeJwt(jwt({ sub: '1' }))).toBeNull(); // no exp
  });

  it('takes the expiry from exp and treats near-expiry as expired', () => {
    const s = sessionFromToken(jwt({ sub: '1', exp: nowSec() + 3600 }))!;
    expect(s.expiresAt).toBe((nowSec() + 3600) * 1000);
    expect(isExpired(s)).toBe(false);
    expect(isExpired({ token: 't', expiresAt: Date.now() + 10_000 })).toBe(true);
  });

  it('never returns an expired stored token', () => {
    saveSession({ token: 'old', expiresAt: Date.now() - 1 });
    expect(loadSession()).toBeNull();
    expect(currentToken()).toBeNull();
    expect(sessionStorage.length).toBe(0);
  });

  it('round-trips a valid session', () => {
    const s = sessionFromToken(jwt({ sub: '1', exp: nowSec() + 3600 }))!;
    saveSession(s);
    expect(currentToken()).toBe(s.token);
  });
});
