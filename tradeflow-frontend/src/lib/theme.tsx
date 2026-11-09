import { useCallback, useEffect, useState } from 'react';

export type ThemeChoice = 'light' | 'dark' | 'system';
const KEY = 'tf_theme';
const CHANGED = 'tf-theme-changed';

function systemDark(): boolean {
  return typeof window !== 'undefined' && !!window.matchMedia?.('(prefers-color-scheme: dark)').matches;
}

function apply(choice: ThemeChoice) {
  const dark = choice === 'dark' || (choice === 'system' && systemDark());
  document.documentElement.classList.toggle('dark', dark);
}

function saved(): ThemeChoice {
  try {
    const v = localStorage.getItem(KEY);
    return v === 'light' || v === 'dark' ? v : 'system';
  } catch { return 'system'; }
}

/** Light / dark / follow the system. theme-init.js applies the saved choice before first paint. */
export function useTheme() {
  const [choice, setChoice] = useState<ThemeChoice>(saved);
  // Keep every component using this hook in step (the toolbar button and the command palette).
  useEffect(() => {
    const sync = () => setChoice(saved());
    window.addEventListener(CHANGED, sync);
    return () => window.removeEventListener(CHANGED, sync);
  }, []);
  useEffect(() => {
    apply(choice);
    if (choice !== 'system') return;
    const mq = window.matchMedia?.('(prefers-color-scheme: dark)');
    const onChange = () => apply('system');
    mq?.addEventListener('change', onChange);
    return () => mq?.removeEventListener('change', onChange);
  }, [choice]);
  const set = useCallback((next: ThemeChoice) => {
    try { localStorage.setItem(KEY, next); } catch { /* not persisted */ }
    setChoice(next);
    window.dispatchEvent(new Event(CHANGED));
  }, []);
  const isDark = choice === 'dark' || (choice === 'system' && systemDark());
  return { choice, isDark, set, toggle: () => set(isDark ? 'light' : 'dark') };
}
