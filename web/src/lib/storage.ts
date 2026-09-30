/**
 * Guarded Web Storage access.
 *
 * `window.localStorage` can throw on *access* (not just on write) in private windows, with
 * site data blocked, or in embedded previews. Preferences and drafts are conveniences, so a
 * storage failure must degrade to "not remembered" — never to a blank page at startup.
 */

type Area = 'local' | 'session';

function area(kind: Area): Storage | null {
  try {
    return kind === 'local' ? window.localStorage : window.sessionStorage;
  } catch {
    return null;
  }
}

export function readStored(key: string, kind: Area = 'local'): string | null {
  try {
    return area(kind)?.getItem(key) ?? null;
  } catch {
    return null;
  }
}

/** Returns false when the value could not be stored; callers may ignore it. */
export function writeStored(key: string, value: string | null, kind: Area = 'local'): boolean {
  try {
    const store = area(kind);
    if (!store) return false;
    if (value === null) store.removeItem(key);
    else store.setItem(key, value);
    return true;
  } catch {
    return false;
  }
}
