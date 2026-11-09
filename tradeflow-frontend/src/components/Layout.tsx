import { Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router';
import { Bell, ChevronDown, ClipboardCheck, KeyRound, LogOut, Menu, Moon, Search, Shield, Sun, UserRound, X } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import { liveApi, marketApi } from '../api/endpoints';
import { useApi } from '../lib/useApi';
import { useMarketStream } from '../lib/useMarketStream';
import { useTheme } from '../lib/theme';
import { CommandPalette } from './CommandPalette';
import { cx, Spinner } from './ui';

type Section = 'demo' | 'learn' | 'live';
interface NavItem { to: string; label: string; end?: boolean; live?: boolean }

/** Primary navigation, in three groups: demo trading, learning, live trading. */
/** Each area has its own colour, so you always know where you are: Demo indigo, Learn amber, Live rose. */
const SECTION_STYLE: Record<Section, { active: string; wash: string }> = {
  demo: { active: 'bg-indigo-100 text-indigo-700', wash: 'from-indigo-100/70 via-sky-50/40' },
  learn: { active: 'bg-amber-100 text-amber-800', wash: 'from-amber-100/70 via-orange-50/40' },
  live: { active: 'bg-rose-100 text-rose-700', wash: 'from-rose-100/70 via-pink-50/40' },
};
const SECTIONS: Section[] = ['demo', 'learn', 'live'];

function sectionOf(path: string): Section | null {
  if (path.startsWith('/live')) return 'live';
  if (path.startsWith('/academy') || path.startsWith('/readiness')) return 'learn';
  if (path.startsWith('/demo')) return 'demo';
  return null;
}

const NAV_GROUPS: NavItem[][] = [
  [
    { to: '/demo', label: 'Dashboard', end: true },
    { to: '/demo/market', label: 'Market' },
    { to: '/demo/orders', label: 'Orders' },
    { to: '/demo/portfolio', label: 'Portfolio' },
    { to: '/demo/watchlist', label: 'Watchlist' },
    { to: '/demo/assistant', label: 'Assistant' },
  ],
  [
    { to: '/academy', label: 'Academy' },
    { to: '/readiness', label: 'Readiness' },
  ],
  [{ to: '/live', label: 'Live trading', live: true }],
];
const ALL_PAGES = NAV_GROUPS.flat();

export function Logo() {
  return (
    <div className="group flex items-center gap-2">
      <svg viewBox="0 0 32 32" className="size-8 transition-transform duration-300 group-hover:-rotate-6" aria-hidden>
        <defs>
          <linearGradient id="tfLogo" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0%" stopColor="#6366f1" /><stop offset="100%" stopColor="#a855f7" />
          </linearGradient>
        </defs>
        <rect width="32" height="32" rx="10" fill="url(#tfLogo)" />
        <path d="M7 21l6-6 4 4 8-8" stroke="#fff" strokeWidth="2.75" fill="none" strokeLinecap="round" strokeLinejoin="round" />
        <circle cx="25" cy="11" r="2.2" fill="#fde68a" />
      </svg>
      <span className="font-display text-lg font-bold tracking-tight text-slate-900">TradeFlow</span>
    </div>
  );
}

function LiveDot() {
  return <span className="size-1.5 rounded-full bg-red-500" data-testid="live-nav-dot" aria-hidden />;
}

function TopLink({ item, section, onNavigate, block }: { item: NavItem; section: Section; onNavigate?: () => void; block?: boolean }) {
  return (
    <NavLink
      to={item.to} end={item.end} onClick={onNavigate}
      className={({ isActive }) => cx(
        'inline-flex items-center gap-1.5 whitespace-nowrap rounded-full text-sm font-medium transition-colors',
        block ? 'w-full px-3.5 py-2' : 'px-3 py-1.5',
        isActive ? SECTION_STYLE[section].active : 'text-slate-600 hover:bg-slate-100 hover:text-slate-900',
      )}
    >
      {item.label}
      {item.live && <LiveDot />}
    </NavLink>
  );
}

function ThemeToggle() {
  const { isDark, toggle } = useTheme();
  return (
    <button onClick={toggle} className="rounded-full p-2 text-slate-500 transition-colors hover:bg-slate-100 hover:text-slate-800"
      aria-label={isDark ? 'Switch to light mode' : 'Switch to dark mode'} title={isDark ? 'Light mode' : 'Dark mode'} data-testid="theme-toggle">
      {isDark ? <Sun className="size-4.5" /> : <Moon className="size-4.5" />}
    </button>
  );
}

/** Account menu: profile, security, staff tools and sign out. */
function AccountMenu() {
  const { user, isAdmin, logout } = useAuth();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const location = useLocation();
  useEffect(() => setOpen(false), [location.pathname]);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false); };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey); };
  }, [open]);
  const reviewer = isAdmin || user?.role === 'READINESS_REVIEWER';
  const item = 'flex items-center gap-2.5 rounded-md px-3 py-2 text-sm text-slate-700 hover:bg-slate-100';
  return (
    <div className="relative" ref={ref}>
      <button
        onClick={() => setOpen((o) => !o)} aria-expanded={open} aria-haspopup="true" aria-label="Account menu"
        data-testid="account-menu"
        className="flex items-center gap-2 rounded-full py-1 pl-1 pr-2.5 text-sm text-slate-700 hover:bg-slate-100"
      >
        <span className="flex size-7 items-center justify-center rounded-full bg-gradient-to-br from-indigo-500 to-violet-500 text-xs font-semibold text-white">
          {user?.name?.slice(0, 1).toUpperCase() ?? '?'}
        </span>
        <span className="hidden max-w-32 truncate font-medium md:inline" data-testid="current-user">{user?.name}</span>
        <ChevronDown className="size-4 text-slate-400" aria-hidden />
      </button>
      {open && (
        <div data-testid="account-menu-panel" className="absolute right-0 top-full z-40 mt-1 w-64 animate-pop rounded-lg border border-slate-200 bg-surface p-1.5 shadow-lg">
          <div className="border-b border-slate-100 px-3 pb-2.5 pt-1.5">
            <p className="truncate text-sm font-medium text-slate-900">{user?.name}</p>
            <p className="truncate text-xs text-slate-500">{user?.email}{isAdmin && ' · Admin'}</p>
          </div>
          <nav aria-label="Account" className="py-1">
            <NavLink to="/settings/profile" className={item}><UserRound className="size-4 text-slate-400" /> Profile</NavLink>
            <NavLink to="/settings/security" className={item}><KeyRound className="size-4 text-slate-400" /> Security</NavLink>
            {reviewer && <NavLink to="/review" className={item}><ClipboardCheck className="size-4 text-slate-400" /> Readiness reviews</NavLink>}
            {isAdmin && <NavLink to="/admin" className={item}><Shield className="size-4 text-slate-400" /> Admin</NavLink>}
          </nav>
          <div className="border-t border-slate-100 pt-1">
            <button onClick={() => logout('manual')} className={cx(item, 'w-full')}>
              <LogOut className="size-4 text-slate-400" /> Sign out
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

export function AppLayout() {
  const [open, setOpen] = useState(false);
  const location = useLocation();
  const openPalette = useRef<(() => void) | null>(null);
  const registerOpener = useCallback((fn: () => void) => { openPalette.current = fn; }, []);
  const section = sectionOf(location.pathname);
  const inLive = section === 'live';
  useEffect(() => setOpen(false), [location.pathname]);
  const streaming = useMarketStream();
  const sim = useApi(() => marketApi.simulation(), [], { intervalMs: 60_000, topics: ['market'] });
  // Live notices (readiness lost, account halted) must be seen even by users who never open the
  // Live dashboard. Best effort: if live-trading-service is not running the badge just stays hidden.
  const liveNotes = useApi(() => liveApi.notifications(), [], { intervalMs: 60_000, topics: ['live'] });
  const unreadLive = liveNotes.data?.filter((n) => !n.readAt).length ?? 0;

  return (
    <div className="relative isolate min-h-dvh">
      <a href="#main-content" onClick={(e) => { e.preventDefault(); document.getElementById('main-content')?.focus(); }}
        className="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-50 focus:rounded-md focus:bg-surface focus:px-3 focus:py-2 focus:text-sm focus:font-medium focus:shadow-lg">
        Skip to main content
      </a>
      <CommandPalette pages={ALL_PAGES} registerOpener={registerOpener} />
      <header className={cx('sticky top-0 z-30 border-b bg-surface/85 backdrop-blur-md', inLive ? 'border-b-rose-200' : 'border-slate-200/80')}>
        {inLive && <div className="h-0.5 bg-red-500" aria-hidden />}
        <div className="mx-auto flex h-14 max-w-7xl items-center gap-4 px-4 sm:px-6 lg:px-8">
          <button onClick={() => setOpen((o) => !o)} className="-ml-1 rounded-md p-1.5 text-slate-600 hover:bg-slate-100 xl:hidden"
            aria-label={open ? 'Close menu' : 'Open menu'} aria-expanded={open}>
            {open ? <X className="size-5" /> : <Menu className="size-5" />}
          </button>
          <NavLink to="/demo" aria-label="TradeFlow home"><Logo /></NavLink>

          {!open && (
            <nav aria-label="Main" className="ml-4 hidden items-center gap-0.5 xl:flex">
              {NAV_GROUPS.map((group, i) => (
                <div key={i} className={cx('flex items-center gap-0.5', i > 0 && 'ml-2 border-l border-slate-200 pl-2')}>
                  {group.map((item) => <TopLink key={item.to} item={item} section={SECTIONS[i]} />)}
                </div>
              ))}
            </nav>
          )}

          <div className="ml-auto flex items-center gap-1.5">
            <button onClick={() => openPalette.current?.()} data-testid="open-command-palette" aria-label="Search and commands (Control or Command K)" title="Search and commands"
              className="flex items-center gap-2 rounded-md p-2 text-sm text-slate-500 hover:bg-slate-100 hover:text-slate-800 md:border md:border-slate-200 md:px-2.5 md:py-1">
              <Search className="size-4" aria-hidden />
              <span className="hidden md:inline">Search</span>
              <kbd className="hidden font-sans text-xs text-slate-400 lg:inline">⌘K</kbd>
            </button>
            <span className="hidden items-center gap-1.5 whitespace-nowrap rounded-md px-2 py-1 text-xs text-slate-500 2xl:flex" title="Demo prices come from TradeFlow's market simulator">
              <span data-testid="stream-status" data-connected={streaming} className={cx('size-1.5 rounded-full', streaming ? 'bg-emerald-500' : 'bg-amber-500')} aria-hidden />
              {sim.data ? <>Sim tick <span className="tabular-nums">{sim.data.currentTick}</span></> : sim.error ? 'Market data unavailable' : '…'}
            </span>
            <NavLink to="/live" className="relative rounded-md p-2 text-slate-500 hover:bg-slate-100 hover:text-slate-800"
              aria-label={unreadLive > 0 ? `${unreadLive} unread live-trading notices` : 'Live-trading notices'}>
              <Bell className="size-4.5" />
              {unreadLive > 0 && (
                <span data-testid="live-unread" className="absolute right-1 top-1 min-w-4 rounded-full bg-red-600 px-1 text-center text-[10px] font-semibold leading-4 text-white">{unreadLive}</span>
              )}
            </NavLink>
            <ThemeToggle />
            <AccountMenu />
          </div>
        </div>

        {open && (
          <div className="border-t border-slate-200 bg-surface xl:hidden">
            <nav aria-label="Main" className="mx-auto max-w-7xl space-y-3 px-4 py-3 sm:px-6">
              {[['Demo trading', NAV_GROUPS[0]], ['Learn', NAV_GROUPS[1]], ['Real money', NAV_GROUPS[2]]].map(([title, items]) => (
                <div key={title as string}>
                  <p className="px-3 pb-1 text-xs font-medium text-slate-400">{title as string}</p>
                  <div className="grid gap-0.5 sm:grid-cols-3">
                    {(items as NavItem[]).map((item) => <TopLink key={item.to} item={item} section={SECTIONS[NAV_GROUPS.indexOf(items as NavItem[])]} block onNavigate={() => setOpen(false)} />)}
                  </div>
                </div>
              ))}
            </nav>
          </div>
        )}
      </header>

      {/* A soft wash in the colour of the current area, behind the top of the page. */}
      {section && <div aria-hidden className={cx('pointer-events-none absolute inset-x-0 top-0 -z-10 h-80 bg-gradient-to-b to-transparent', SECTION_STYLE[section].wash)} />}
      <main id="main-content" tabIndex={-1} key={location.pathname} className="mx-auto max-w-7xl outline-none animate-enter px-4 py-6 sm:px-6 lg:px-8 lg:py-8">
        <Suspense fallback={<Spinner />}><Outlet /></Suspense>
        <footer className="mt-12 border-t border-slate-200 pt-4 text-xs text-slate-400">
          TradeFlow · demo prices are simulated · not investment advice
        </footer>
      </main>
    </div>
  );
}
