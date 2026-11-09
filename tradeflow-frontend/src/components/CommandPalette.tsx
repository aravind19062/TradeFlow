import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react';
import { useNavigate } from 'react-router';
import { marketApi } from '../api/endpoints';
import { useAuth } from '../auth/AuthContext';
import { useApi } from '../lib/useApi';
import { useTheme } from '../lib/theme';
import { cx, Modal } from './ui';

interface Command { id: string; label: string; group: string; keywords?: string; run: () => void }

/** Two-key "go to" shortcuts. They only navigate: no shortcut can place or change an order. */
const GO_TO: { key: string; label: string; to: string }[] = [
  { key: 'd', label: 'Dashboard', to: '/demo' },
  { key: 'm', label: 'Market', to: '/demo/market' },
  { key: 'o', label: 'Orders', to: '/demo/orders' },
  { key: 'p', label: 'Portfolio', to: '/demo/portfolio' },
  { key: 'w', label: 'Watchlist', to: '/demo/watchlist' },
  { key: 'a', label: 'Academy', to: '/academy' },
  { key: 'l', label: 'Live trading', to: '/live' },
  { key: 's', label: 'Security', to: '/settings/security' },
  { key: 'u', label: 'Profile', to: '/settings/profile' },
];

function isTyping(target: EventTarget | null): boolean {
  return target instanceof HTMLElement
    && (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName));
}

const dialogOpen = () => document.querySelector('[role="dialog"]') !== null;

/** Ctrl/Cmd+K command palette, "g then a letter" navigation and the ? shortcut list. */
export function CommandPalette({ pages, registerOpener }: {
  pages: { to: string; label: string }[];
  registerOpener?: (open: () => void) => void;
}) {
  const [palette, setPalette] = useState(false);
  const [help, setHelp] = useState(false);
  const navigate = useNavigate();
  const paletteOpen = useRef(false);
  paletteOpen.current = palette;

  useEffect(() => registerOpener?.(() => setPalette(true)), [registerOpener]);

  useEffect(() => {
    let awaitingKey = false;
    let timer: number | undefined;
    const reset = () => { awaitingKey = false; window.clearTimeout(timer); };

    function onKey(e: KeyboardEvent) {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        // Never cover a dialog the user is filling in, such as the order confirmation.
        if (dialogOpen() && !paletteOpen.current) return;
        e.preventDefault();
        reset();
        setPalette((open) => !open);
        return;
      }
      if (e.metaKey || e.ctrlKey || e.altKey || isTyping(e.target) || dialogOpen()) return;
      if (awaitingKey) {
        const target = GO_TO.find((g) => g.key === e.key.toLowerCase());
        reset();
        if (target) { e.preventDefault(); navigate(target.to); }
        return;
      }
      if (e.key === '?') { e.preventDefault(); setHelp(true); return; }
      if (e.key.toLowerCase() === 'g') {
        awaitingKey = true;
        timer = window.setTimeout(reset, 1200);
      }
    }
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('keydown', onKey); reset(); };
  }, [navigate]);

  return (
    <>
      <Modal open={palette} onClose={() => setPalette(false)} title="Command palette">
        <Palette pages={pages} onClose={() => setPalette(false)} onHelp={() => { setPalette(false); setHelp(true); }} />
      </Modal>
      <Modal open={help} onClose={() => setHelp(false)} title="Keyboard shortcuts">
        <dl className="space-y-2" data-testid="shortcut-list">
          <Shortcut keys={['Ctrl/⌘', 'K']} label="Open the command palette" />
          <Shortcut keys={['?']} label="Show this list" />
          <Shortcut keys={['Esc']} label="Close a dialog" />
          {GO_TO.map((g) => <Shortcut key={g.key} keys={['g', g.key]} label={`Go to ${g.label}`} />)}
        </dl>
      </Modal>
    </>
  );
}

function Shortcut({ keys, label }: { keys: string[]; label: string }) {
  return (
    <div className="flex items-center justify-between gap-4">
      <dt className="text-slate-600">{label}</dt>
      <dd className="flex gap-1">
        {keys.map((k) => <kbd key={k} className="rounded border border-slate-200 bg-slate-50 px-1.5 py-0.5 font-sans text-xs text-slate-700">{k}</kbd>)}
      </dd>
    </div>
  );
}

function Palette({ pages, onClose, onHelp }: { pages: { to: string; label: string }[]; onClose: () => void; onHelp: () => void }) {
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const navigate = useNavigate();
  const { user, isAdmin, logout } = useAuth();
  const { isDark, toggle } = useTheme();
  const instruments = useApi(() => marketApi.instruments(), []);
  const listId = useId();
  const listRef = useRef<HTMLUListElement>(null);

  const commands = useMemo<Command[]>(() => {
    const go = (id: string, label: string, to: string, group = 'Pages', keywords?: string): Command =>
      ({ id, label, group, keywords, run: () => navigate(to) });
    const reviewer = user?.role === 'READINESS_REVIEWER' || isAdmin;
    return [
      ...pages.map((p) => go(p.to, p.label, p.to)),
      go('/settings/profile', 'Profile', '/settings/profile', 'Account', 'bank email details'),
      go('/settings/security', 'Security', '/settings/security', 'Account', '2fa two-factor password sessions devices'),
      go('/settings/activity', 'Account activity', '/settings/activity', 'Account', 'audit log history'),
      ...(reviewer ? [go('/review', 'Readiness reviews', '/review', 'Account')] : []),
      ...(isAdmin ? [go('/admin', 'Admin', '/admin', 'Account')] : []),
      { id: 'theme', label: isDark ? 'Switch to light mode' : 'Switch to dark mode', group: 'Actions', keywords: 'theme appearance', run: toggle },
      { id: 'help', label: 'Keyboard shortcuts', group: 'Actions', keywords: 'help keys', run: onHelp },
      { id: 'logout', label: 'Sign out', group: 'Actions', keywords: 'log out logout', run: () => { void logout('manual'); } },
    ];
  }, [pages, navigate, user?.role, isAdmin, isDark, toggle, onHelp, logout]);

  const results = useMemo(() => {
    const q = query.trim().toLowerCase();
    const matches = (text: string) => text.toLowerCase().includes(q);
    const found = q ? commands.filter((c) => matches(`${c.label} ${c.keywords ?? ''}`)) : commands;
    const stocks: Command[] = q
      ? (instruments.data ?? []).filter((i) => matches(`${i.symbol} ${i.name}`)).slice(0, 8).map((i) => ({
        id: `stock-${i.symbol}`, label: `${i.symbol} · ${i.name}`, group: 'Instruments', run: () => navigate(`/demo/market/${encodeURIComponent(i.symbol)}`),
      }))
      : [];
    return [...found, ...stocks];
  }, [query, commands, instruments.data, navigate]);

  const current = Math.min(active, Math.max(results.length - 1, 0));
  useEffect(() => {
    listRef.current?.querySelector('[aria-selected="true"]')?.scrollIntoView?.({ block: 'nearest' });
  }, [current]);

  function execute(command: Command | undefined) {
    if (!command) return;
    // The help command swaps dialogs itself; the others just close this one.
    if (command.id !== 'help') onClose();
    command.run();
  }

  function onKeyDown(e: ReactKeyboardEvent<HTMLInputElement>) {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      if (results.length === 0) return;
      const step = e.key === 'ArrowDown' ? 1 : -1;
      setActive((current + step + results.length) % results.length);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      execute(results[current]);
    }
  }

  const optionId = (i: number) => `${listId}-${i}`;
  return (
    <div data-testid="command-palette">
      <input
        data-autofocus role="combobox" aria-expanded aria-controls={listId} aria-autocomplete="list" aria-label="Search pages, actions and instruments"
        aria-activedescendant={results.length > 0 ? optionId(current) : undefined}
        value={query} onChange={(e) => { setQuery(e.target.value); setActive(0); }} onKeyDown={onKeyDown}
        placeholder="Search pages, actions, instruments…" autoComplete="off"
        className="w-full rounded-lg border border-slate-200 bg-surface px-3 py-2 text-sm text-slate-900 outline-none focus:border-indigo-400 focus:ring-2 focus:ring-indigo-100"
      />
      <ul id={listId} ref={listRef} role="listbox" aria-label="Results" className="mt-2 max-h-72 overflow-y-auto">
        {results.map((c, i) => (
          <li
            key={c.id} id={optionId(i)} role="option" aria-selected={i === current}
            onMouseEnter={() => setActive(i)} onClick={() => execute(c)}
            className={cx('flex cursor-pointer items-center justify-between gap-3 rounded-md px-3 py-2', i === current && 'bg-indigo-50 text-indigo-800')}
          >
            <span className="truncate">{c.label}</span>
            <span className="shrink-0 text-xs text-slate-400">{c.group}</span>
          </li>
        ))}
        {results.length === 0 && <li className="px-3 py-6 text-center text-slate-500">No matches</li>}
      </ul>
    </div>
  );
}
