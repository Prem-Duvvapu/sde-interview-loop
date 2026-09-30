import { describe, expect, it } from 'vitest';
import { normalizeProfile } from './profiles';

describe('normalizeProfile', () => {
  it('regression: reads the snake_case shape the backend actually sends', () => {
    const p = normalizeProfile({
      id: 'linkedin',
      display_name: 'LinkedIn',
      target_role: { title: 'Software Engineer', level_code: 'SWE (SDE-2 equivalent)' },
      calibration: { confidence: 'seeded-unverified', last_updated: '2026-08-22' },
      loop: { total_wall_clock_min: 240, rounds: [{ ordinal: 1, module: 'dsa', duration_min: 60 }] },
    });
    expect(p.displayName).toBe('LinkedIn');
    expect(p.targetRole?.levelCode).toBe('SWE (SDE-2 equivalent)');
    expect(p.calibration?.lastUpdated).toBe('2026-08-22');
    expect(p.loop?.totalWallClockMin).toBe(240);
    expect(p.loop?.rounds).toHaveLength(1);
  });

  it('still accepts camelCase', () => {
    expect(normalizeProfile({ id: 'x', displayName: 'X Corp' }).displayName).toBe('X Corp');
  });
});
