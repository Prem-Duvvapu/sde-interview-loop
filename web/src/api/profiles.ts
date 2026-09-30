import type { CompanyProfile, ProfileRound } from './types';

/**
 * The backend serialises profiles exactly as the YAML is written (snake_case: `display_name`,
 * `target_role.level_code`, `loop.total_wall_clock_min`, …) while the client types are
 * camelCase. Before this normaliser existed every company rendered as its raw id and the
 * level/role chips never appeared (RCA #16). Accept either spelling so a later backend change
 * to camelCase cannot silently break the UI again.
 */
type Raw = Record<string, unknown>;

const rec = (v: unknown): Raw | null => (v && typeof v === 'object' && !Array.isArray(v) ? (v as Raw) : null);
const pick = (o: Raw | null, camel: string, snake: string): unknown => (o ? (o[camel] ?? o[snake]) : undefined);
const strOrNull = (v: unknown): string | null => (typeof v === 'string' ? v : null);
const numOrNull = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);

export function normalizeProfile(input: unknown): CompanyProfile {
  const raw = rec(input) ?? {};
  const role = rec(pick(raw, 'targetRole', 'target_role'));
  const calibration = rec(raw.calibration);
  const loop = rec(raw.loop);
  const readiness = rec(raw.readiness);
  return {
    id: String(raw.id ?? ''),
    displayName: strOrNull(pick(raw, 'displayName', 'display_name')),
    targetRole: role
      ? {
          title: strOrNull(role.title),
          levelCode: strOrNull(pick(role, 'levelCode', 'level_code')),
          locationContext: strOrNull(pick(role, 'locationContext', 'location_context')),
        }
      : null,
    difficulty: strOrNull(raw.difficulty),
    calibration: calibration
      ? {
          confidence: strOrNull(calibration.confidence),
          lastUpdated: strOrNull(pick(calibration, 'lastUpdated', 'last_updated')),
          notes: strOrNull(calibration.notes),
        }
      : null,
    emphasis: (rec(raw.emphasis) as Record<string, number> | null) ?? null,
    loop: loop
      ? {
          totalWallClockMin: numOrNull(pick(loop, 'totalWallClockMin', 'total_wall_clock_min')),
          difficultyCurve: strOrNull(pick(loop, 'difficultyCurve', 'difficulty_curve')),
          rounds: Array.isArray(loop.rounds) ? (loop.rounds as ProfileRound[]) : null,
        }
      : null,
    quirks: Array.isArray(raw.quirks) ? (raw.quirks as CompanyProfile['quirks']) : null,
    readiness: readiness
      ? {
          barBand: strOrNull(pick(readiness, 'barBand', 'bar_band')),
          minSessionsForConfidence: numOrNull(pick(readiness, 'minSessionsForConfidence', 'min_sessions_for_confidence')),
        }
      : null,
  };
}
