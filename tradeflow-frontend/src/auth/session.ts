// Access-token handling. The backend issues short-lived (15 min) RS256 access tokens and keeps
// the session alive with a rotating refresh token in an HttpOnly, SameSite=Strict cookie that
// scripts cannot read. The access token therefore lives ONLY in memory: nothing is written to
// localStorage or sessionStorage, and a page reload restores the session through the cookie.
// A separate 10-minute step-up token (password + TOTP) authorises live trading.

/** Treat a token as expired slightly early so a request never leaves with a dying token. */
const EXPIRY_SKEW_MS = 30_000;

export interface Session {
  token: string;
  /** Epoch millis taken from the JWT `exp` claim. */
  expiresAt: number;
}

export interface JwtClaims {
  sub: string;
  email?: string;
  name?: string;
  roles?: string[];
  exp: number;
  iat?: number;
  iss?: string;
  aud?: string | string[];
  sid?: string;
  amr?: string[];
}

/** Reads the (unverified) payload of a JWT. Verification is the backend's job. */
export function decodeJwt(token: string): JwtClaims | null {
  const parts = token.split('.');
  if (parts.length !== 3) return null;
  try {
    const b64 = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    const padded = b64 + '='.repeat((4 - (b64.length % 4)) % 4);
    const json = decodeURIComponent(
      Array.from(atob(padded), (c) => '%' + c.charCodeAt(0).toString(16).padStart(2, '0')).join(''),
    );
    const claims = JSON.parse(json) as JwtClaims;
    return typeof claims.exp === 'number' && typeof claims.sub === 'string' ? claims : null;
  } catch {
    return null;
  }
}

export function sessionFromToken(token: string): Session | null {
  const claims = decodeJwt(token);
  return claims ? { token, expiresAt: claims.exp * 1000 } : null;
}

export function isExpired(session: Session, now = Date.now()): boolean {
  return now >= session.expiresAt - EXPIRY_SKEW_MS;
}

let current: Session | null = null;
let stepUp: Session | null = null;

export function setSession(session: Session | null): void {
  current = session;
  if (!session) stepUp = null;
}

export function getSession(): Session | null {
  return current && !isExpired(current) ? current : null;
}

/** The token to send right now, or null if there is none or it has expired. */
export function currentToken(): string | null {
  return getSession()?.token ?? null;
}

export function clearSession(): void {
  current = null;
  stepUp = null;
}

/** Live-trading step-up token (audience tradeflow-live), held in memory for its 10 minutes. */
export function setStepUp(session: Session | null): void {
  stepUp = session;
}

export function currentStepUp(): Session | null {
  return stepUp && !isExpired(stepUp, Date.now()) ? stepUp : null;
}
