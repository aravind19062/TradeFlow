import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { setUnauthorizedListener } from '../api/client';
import { authApi } from '../api/endpoints';
import type { LoginRequest, RegisterRequest, TokenResponse, UserResponse } from '../api/types';
import { clearSession, loadSession, saveSession, sessionFromToken, type Session } from './session';

export type AuthStatus = 'loading' | 'authenticated' | 'anonymous';
export type LogoutReason = 'manual' | 'expired';

interface AuthState {
  status: AuthStatus;
  /** Always the backend's view of the user (login/register response or GET /api/users/me). */
  user: UserResponse | null;
  isAdmin: boolean;
  expiresAt: number | null;
  logoutReason: LogoutReason | null;
  /** True right after this tab registered the account (for first-run messaging). */
  isNewUser: boolean;
  login: (body: LoginRequest) => Promise<void>;
  register: (body: RegisterRequest) => Promise<void>;
  logout: (reason?: LogoutReason) => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(() => loadSession());
  const [user, setUser] = useState<UserResponse | null>(null);
  const [status, setStatus] = useState<AuthStatus>(() => (loadSession() ? 'loading' : 'anonymous'));
  const [logoutReason, setLogoutReason] = useState<LogoutReason | null>(null);
  const [isNewUser, setIsNewUser] = useState(false);

  const logout = useCallback((reason: LogoutReason = 'manual') => {
    // The backend has no logout/revocation endpoint: signing out discards the token locally.
    clearSession();
    setSession(null);
    setUser(null);
    setStatus('anonymous');
    setLogoutReason(reason);
    setIsNewUser(false);
  }, []);

  // Any 401 on a protected request ends the session.
  useEffect(() => {
    setUnauthorizedListener(() => logout('expired'));
    return () => setUnauthorizedListener(null);
  }, [logout]);

  // On page load with a stored token: confirm it with the backend and fetch the profile.
  useEffect(() => {
    if (!session || user) return;
    let cancelled = false;
    authApi.me()
      .then((me) => { if (!cancelled) { setUser(me); setStatus('authenticated'); } })
      .catch(() => { if (!cancelled) logout('expired'); });
    return () => { cancelled = true; };
  }, [session, user, logout]);

  // Sign out exactly when the token expires (no refresh token exists).
  useEffect(() => {
    if (!session) return;
    const ms = session.expiresAt - Date.now() - 30_000;
    const timer = window.setTimeout(() => logout('expired'), Math.max(0, ms));
    return () => window.clearTimeout(timer);
  }, [session, logout]);

  const accept = useCallback((res: TokenResponse, registered: boolean) => {
    const next = sessionFromToken(res.accessToken);
    if (!next) throw new Error('The server returned an unreadable access token.');
    saveSession(next);
    setSession(next);
    setUser(res.user);
    setStatus('authenticated');
    setLogoutReason(null);
    setIsNewUser(registered);
  }, []);

  const login = useCallback(async (body: LoginRequest) => accept(await authApi.login(body), false), [accept]);
  const register = useCallback(async (body: RegisterRequest) => accept(await authApi.register(body), true), [accept]);

  const value = useMemo<AuthState>(() => ({
    status,
    user,
    isAdmin: user?.role === 'ADMIN',
    expiresAt: session?.expiresAt ?? null,
    logoutReason,
    isNewUser,
    login,
    register,
    logout,
  }), [status, user, session, logoutReason, isNewUser, login, register, logout]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}
