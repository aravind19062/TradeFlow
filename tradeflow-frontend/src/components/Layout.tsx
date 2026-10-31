import { Suspense, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router';
import {
  Bell, Bot, ChartCandlestick, Radio, ClipboardCheck, GraduationCap, KeyRound, LayoutDashboard, ListOrdered, LogOut, Menu, Shield, Wallet, X,
} from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { marketApi } from '../api/endpoints';
import { useApi } from '../lib/useApi';
import { cx, Spinner } from './ui';
import { EnvironmentBadge } from './environment';

interface NavItem { to: string; label: string; icon: typeof LayoutDashboard; end?: boolean }
interface NavSection { title: string; env?: 'DEMO' | 'LIVE'; items: NavItem[] }

const LEARN_NAV: NavSection = {
  title: 'Learn', items: [
    { to: '/academy', label: 'Trading Academy', icon: GraduationCap },
    { to: '/readiness', label: 'Readiness', icon: ClipboardCheck },
  ],
};

const DEMO_NAV: NavSection = {
  title: 'Demo trading', env: 'DEMO', items: [
    { to: '/demo', label: 'Dashboard', icon: LayoutDashboard, end: true },
    { to: '/demo/market', label: 'Market watch', icon: ChartCandlestick },
    { to: '/demo/orders', label: 'Orders', icon: ListOrdered },
    { to: '/demo/portfolio', label: 'Portfolio', icon: Wallet },
    { to: '/demo/watchlist', label: 'Watchlist & alerts', icon: Bell },
    { to: '/demo/assistant', label: 'AI assistant', icon: Bot },
  ],
};

const LIVE_NAV: NavSection = {
  title: 'Live trading', env: 'LIVE', items: [
    { to: '/live', label: 'Live dashboard', icon: Radio },
  ],
};

export function Logo({ dark = true }: { dark?: boolean }) {
  return (
    <div className="flex items-center gap-2.5">
      <svg viewBox="0 0 32 32" className="size-8" aria-hidden>
        <rect width="32" height="32" rx="7" fill={dark ? '#1e293b' : '#0f172a'} />
        <path d="M6 21l6-6 4 4 9-9" stroke="#34d399" strokeWidth="3" fill="none" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      <span className={cx('text-lg font-semibold tracking-tight', dark ? 'text-white' : 'text-slate-900')}>TradeFlow</span>
    </div>
  );
}

function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  const { user, isAdmin, logout } = useAuth();
  const sim = useApi(() => marketApi.simulation(), [], { intervalMs: 30_000, topics: ['market'] });
  const sections: NavSection[] = [DEMO_NAV, LEARN_NAV, LIVE_NAV, { title: 'Account', items: [{ to: '/settings/security', label: 'Security', icon: KeyRound }] }];
  const staff: NavItem[] = [];
  if (isAdmin || user?.role === 'READINESS_REVIEWER') staff.push({ to: '/review', label: 'Readiness reviews', icon: ClipboardCheck });
  if (isAdmin) staff.push({ to: '/admin', label: 'Admin', icon: Shield });
  if (staff.length) sections.push({ title: 'Administration', items: staff });
  return (
    <div className="flex h-full flex-col bg-[#0b1220] text-slate-300">
      <div className="px-5 py-5"><Logo /></div>
      <nav className="flex-1 space-y-5 overflow-y-auto px-3" aria-label="Main">
        {sections.map((section) => (
          <div key={section.title}>
            <p className="mb-1.5 flex items-center gap-2 px-3 text-[11px] font-semibold uppercase tracking-wider text-slate-500">
              {section.title} {section.env && <EnvironmentBadge env={section.env} size="xs" />}
            </p>
            <div className="space-y-0.5">
              {section.items.map(({ to, label, icon: Icon, end }) => (
                <NavLink
                  key={to} to={to} end={end} onClick={onNavigate}
                  className={({ isActive }) => cx('flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
                    isActive ? 'bg-slate-800 text-white' : 'text-slate-400 hover:bg-slate-800/60 hover:text-slate-100')}
                >
                  <Icon className="size-4.5" aria-hidden /> {label}
                </NavLink>
              ))}
            </div>
          </div>
        ))}
      </nav>
      <div className="mx-3 mb-3 rounded-lg bg-slate-800/50 px-3 py-2.5 text-xs text-slate-400">
        <div className="flex items-center gap-2">
          <span className="relative flex size-2">
            <span className="absolute inline-flex size-full animate-ping rounded-full bg-emerald-400 opacity-60" />
            <span className="relative inline-flex size-2 rounded-full bg-emerald-400" />
          </span>
          Simulated market
        </div>
        <p className="mt-1 tabular-nums">{sim.data ? `Tick ${sim.data.currentTick}` : sim.error ? 'Market data unavailable' : '…'}</p>
      </div>
      <div className="border-t border-slate-800 px-4 py-4">
        <div className="flex items-center gap-3">
          <div className="flex size-9 shrink-0 items-center justify-center rounded-full bg-slate-700 text-sm font-semibold text-white">
            {user?.name?.slice(0, 1).toUpperCase() ?? '?'}
          </div>
          <div className="min-w-0 flex-1">
            <p className="truncate text-sm font-medium text-white" data-testid="current-user">{user?.name}</p>
            <p className="truncate text-xs text-slate-400">{user?.email}{isAdmin && ' · Admin'}</p>
          </div>
          <button onClick={() => logout('manual')} className="rounded-md p-2 text-slate-400 hover:bg-slate-800 hover:text-white" aria-label="Sign out" title="Sign out">
            <LogOut className="size-4" />
          </button>
        </div>
      </div>
    </div>
  );
}

export function AppLayout() {
  const [open, setOpen] = useState(false);
  const location = useLocation();
  return (
    <div className="min-h-dvh lg:pl-64">
      <aside className="fixed inset-y-0 left-0 hidden w-64 lg:block"><Sidebar /></aside>

      {open && (
        <div className="fixed inset-0 z-40 lg:hidden">
          <div className="absolute inset-0 bg-slate-950/60" onClick={() => setOpen(false)} />
          <aside className="absolute inset-y-0 left-0 w-72 shadow-xl">
            <button className="absolute right-3 top-5 rounded-md p-1 text-slate-400 hover:text-white" onClick={() => setOpen(false)} aria-label="Close menu">
              <X className="size-5" />
            </button>
            <Sidebar onNavigate={() => setOpen(false)} />
          </aside>
        </div>
      )}

      <header className="sticky top-0 z-30 flex items-center justify-between border-b border-slate-200 bg-white/90 px-4 py-3 backdrop-blur lg:hidden">
        <button onClick={() => setOpen(true)} className="rounded-md p-1.5 text-slate-600 hover:bg-slate-100" aria-label="Open menu">
          <Menu className="size-5" />
        </button>
        <Logo dark={false} />
        <span className="w-8" />
      </header>

      <main key={location.pathname} className="mx-auto max-w-7xl px-4 py-6 sm:px-6 lg:px-8 lg:py-8">
        <Suspense fallback={<Spinner />}><Outlet /></Suspense>
        <footer className="mt-12 border-t border-slate-200 pt-4 text-xs text-slate-400">
          TradeFlow · demo prices are simulated · not investment advice
        </footer>
      </main>
    </div>
  );
}
