import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { ApiError, refreshOnce, setRefreshHandler, setUnauthorizedListener } from '../api/client';
import { authApi } from '../api/endpoints';
import type { LoginRequest, RegisterRequest, TokenResponse, UserResponse } from '../api/types';
import { clearSession, sessionFromToken, setSession } from './session';

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous';
export type LogoutReason = 'manual' | 'expired';

/** Result of the password step: signed in, or a second factor is needed. */
export type LoginOutcome = { mfaRequired: false } | { mfaRequired: true; mfaToken: string; expiresInSeconds: number };

interface AuthState {
  status: AuthStatus;
  /** Always the backend's view of the user (login/register/refresh response or GET /api/users/me). */
  user: UserResponse | null;
  isAdmin: boolean;
  expiresAt: number | null;
  logoutReason: LogoutReason | null;
  /** True right after this tab registered the account (for first-run messaging). */
  isNewUser: boolean;
  login: (body: LoginRequest) => Promise<LoginOutcome>;
  completeMfa: (mfaToken: string, factor: { code?: string; recoveryCode?: string }) => Promise<void>;
  register: (body: RegisterRequest) => Promise<void>;
  logout: (reason?: LogoutReason) => Promise<void>;
  /** Called after "sign out everywhere": the server already ended the session. */
  forgetSession: (reason?: LogoutReason) => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<UserResponse | null>(null);
  const [expiresAt, setExpiresAt] = useState<number | null>(null);
  const [status, setStatus] = useState<AuthStatus>('loading');
  const [logoutReason, setLogoutReason] = useState<LogoutReason | null>(null);
  const [isNewUser, setIsNewUser] = useState(false);
  const refreshTimer = useRef<number | undefined>(undefined);

  const forgetSession = useCallback((reason: LogoutReason = 'manual') => {
    clearSession();
    window.clearTimeout(refreshTimer.current);
    setUser(null);
    setExpiresAt(null);
    setStatus('anonymous');
    setLogoutReason(reason);
    setIsNewUser(false);
  }, []);

  const accept = useCallback((res: TokenResponse, registered = false) => {
    const next = sessionFromToken(res.accessToken);
    if (!next) throw new Error('The server returned an unreadable access token.');
    setSession(next);
    setExpiresAt(next.expiresAt);
    setUser(res.user);
    setStatus('authenticated');
    setLogoutReason(null);
    if (registered) setIsNewUser(true);
  }, []);

  /** Exchanges the HttpOnly refresh cookie for a new access token. */
  const refresh = useCallback(async (): Promise<boolean> => {
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        accept(await authApi.refresh());
        return true;
      } catch (e) {
        // Another tab rotated the shared cookie a moment ago; the browser now holds the new one.
        if (e instanceof ApiError && e.code === 'REFRESH_RACE' && attempt === 0) {
          await new Promise((r) => setTimeout(r, 400));
          continue;
        }
        return false;
      }
    }
    return false;
  }, [accept]);

  // The API client refreshes once on a 401 and ends the session if that fails.
  useEffect(() => {
    setRefreshHandler(refresh);
    setUnauthorizedListener(() => forgetSession('expired'));
    return () => { setRefreshHandler(null); setUnauthorizedListener(null); };
  }, [refresh, forgetSession]);

  // On load there is never an access token (memory only): resume through the cookie, if any.
  useEffect(() => {
    let cancelled = false;
    refreshOnce().then((ok) => { if (!cancelled && !ok) setStatus('anonymous'); });
    return () => { cancelled = true; };
  }, []);

  // Refresh a minute before the 15-minute access token expires; if that fails, sign out.
  useEffect(() => {
    if (status !== 'authenticated' || !expiresAt) return;
    window.clearTimeout(refreshTimer.current);
    refreshTimer.current = window.setTimeout(async () => {
      if (!(await refreshOnce())) forgetSession('expired');
    }, Math.max(1_000, expiresAt - Date.now() - 60_000));
    return () => window.clearTimeout(refreshTimer.current);
  }, [status, expiresAt, forgetSession]);

  const login = useCallback(async (body: LoginRequest): Promise<LoginOutcome> => {
    const res = await authApi.login(body);
    if (res.mfaRequired) {
      return { mfaRequired: true, mfaToken: res.mfaToken!, expiresInSeconds: res.mfaExpiresInSeconds ?? 300 };
    }
    accept({ accessToken: res.accessToken!, tokenType: res.tokenType!, expiresInSeconds: res.expiresInSeconds!, user: res.user! });
    return { mfaRequired: false };
  }, [accept]);

  const completeMfa = useCallback(async (mfaToken: string, factor: { code?: string; recoveryCode?: string }) => {
    accept(await authApi.loginMfa(mfaToken, factor));
  }, [accept]);

  const register = useCallback(async (body: RegisterRequest) => accept(await authApi.register(body), true), [accept]);

  const logout = useCallback(async (reason: LogoutReason = 'manual') => {
    // Revokes the session server-side (refresh token family) and clears the cookie.
    try { await authApi.logout(); } catch { /* signing out locally regardless */ }
    forgetSession(reason);
  }, [forgetSession]);

  const value = useMemo<AuthState>(() => ({
    status, user, isAdmin: user?.role === 'ADMIN', expiresAt, logoutReason, isNewUser,
    login, completeMfa, register, logout, forgetSession,
  }), [status, user, expiresAt, logoutReason, isNewUser, login, completeMfa, register, logout, forgetSession]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
