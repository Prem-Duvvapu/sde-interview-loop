import { useEffect, useRef, useState } from 'react';

/** Re-renders on an interval — used only by the round timer, so it stays cheap. */
export function useTicker(intervalMs: number, active: boolean): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return;
    setNow(Date.now());
    const id = window.setInterval(() => setNow(Date.now()), intervalMs);
    return () => window.clearInterval(id);
  }, [intervalMs, active]);
  return now;
}

/**
 * Trailing-edge debounce. The editor writes its buffer to a ref on every keystroke
 * (no render), and only this debounced mirror drives React state — which is what
 * keeps typing in Monaco smooth.
 */
export function useDebounced<T>(value: T, delayMs: number): T {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = window.setTimeout(() => setDebounced(value), delayMs);
    return () => window.clearTimeout(id);
  }, [value, delayMs]);
  return debounced;
}

/** Autoscrolls a scroll container while the user is already pinned to the bottom. */
export function useStickyScroll<T extends HTMLElement>(dep: unknown) {
  const ref = useRef<T | null>(null);
  const pinnedRef = useRef(true);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const onScroll = () => {
      pinnedRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
    };
    el.addEventListener('scroll', onScroll, { passive: true });
    return () => el.removeEventListener('scroll', onScroll);
  }, []);

  useEffect(() => {
    const el = ref.current;
    if (el && pinnedRef.current) el.scrollTop = el.scrollHeight;
  }, [dep]);

  return ref;
}
