// Access-token storage. The backend issues a single HS256 bearer token (8 h by default)
// with no refresh token and no logout endpoint, so the client's job is to: keep it in
// sessionStorage (gone when the tab/window closes, never shared with other origins),
// never trust a token past its `exp`, and drop it on the first 401.

const KEY = 'tradeflow.session';
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

export function loadSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Session;
    if (typeof parsed.token !== 'string' || typeof parsed.expiresAt !== 'number' || isExpired(parsed)) {
      sessionStorage.removeItem(KEY);
      return null;
    }
    return parsed;
  } catch {
    sessionStorage.removeItem(KEY);
    return null;
  }
}

export function saveSession(session: Session): void {
  sessionStorage.setItem(KEY, JSON.stringify(session));
}

export function clearSession(): void {
  sessionStorage.removeItem(KEY);
}

/** The token to send right now, or null if there is none or it has expired. */
export function currentToken(): string | null {
  return loadSession()?.token ?? null;
}
