import { describe, expect, it, vi } from 'vitest';
import { readStored, writeStored } from './storage';

describe('guarded storage', () => {
  it('round-trips values', () => {
    expect(writeStored('k', 'v')).toBe(true);
    expect(readStored('k')).toBe('v');
    writeStored('k', null);
    expect(readStored('k')).toBeNull();
  });

  it('degrades to "not remembered" when storage access throws', () => {
    const getter = vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new DOMException('denied', 'SecurityError');
    });
    expect(readStored('k')).toBeNull();
    expect(writeStored('k', 'v')).toBe(false);
    getter.mockRestore();
  });

  it('survives setItem quota errors', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('full', 'QuotaExceededError');
    });
    expect(writeStored('k', 'v')).toBe(false);
  });
});
