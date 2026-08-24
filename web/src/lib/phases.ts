import type { ModuleTypeId } from '../api/types';

/**
 * Phase sequences per module, from PROJECT_PLAN.md §1.2.
 *
 * The interviewer is a phase machine, not a chatbot: the phase strip is the UI's
 * expression of that. Backend enum names (RoundPhase) are used verbatim as keys so
 * a `phase_advanced` frame maps straight onto a step.
 */
export const PHASE_SEQUENCES: Record<ModuleTypeId, string[]> = {
  dsa: ['BRIEFING', 'CLARIFYING', 'APPROACH', 'CODING', 'COMPLEXITY', 'EDGE_CASES', 'FOLLOW_UP', 'WRAP'],
  lld: ['BRIEFING', 'REQUIREMENTS', 'CLASS_MODEL', 'DEEP_DIVE', 'EXTENSION', 'WRAP'],
  hld: ['BRIEFING', 'REQUIREMENTS', 'ESTIMATION', 'HIGH_LEVEL', 'DEEP_DIVE', 'BOTTLENECK', 'WRAP'],
  cs_fundamentals: ['BRIEFING', 'RAPID_FIRE', 'WRAP'],
  java_deep_dive: ['BRIEFING', 'SCENARIO', 'PROBE', 'DEPTH_LADDER', 'TRADE_OFF', 'WRAP'],
  behavioral: ['BRIEFING', 'RAPID_FIRE', 'WRAP'],
};

export const MODULE_LABELS: Record<ModuleTypeId, string> = {
  dsa: 'DSA',
  lld: 'Low-level design',
  hld: 'High-level design',
  cs_fundamentals: 'CS fundamentals',
  java_deep_dive: 'Java deep dive',
  behavioral: 'Behavioral',
};

/** Modules the v1 interview loop can actually run. */
export const RUNNABLE_MODULES: ModuleTypeId[] = ['dsa', 'lld', 'hld', 'cs_fundamentals', 'java_deep_dive'];

const PHASE_LABEL_OVERRIDES: Record<string, string> = {
  DSA: 'DSA',
  HLD: 'HLD',
  LLD: 'LLD',
};

export function phaseLabel(phase: string): string {
  if (PHASE_LABEL_OVERRIDES[phase]) return PHASE_LABEL_OVERRIDES[phase];
  return phase
    .toLowerCase()
    .split('_')
    .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
    .join(' ');
}

export function isModuleType(value: string): value is ModuleTypeId {
  return value in PHASE_SEQUENCES;
}

/**
 * Sequence for a module, with a phase the sequence does not know about spliced in
 * rather than dropped — a backend that invents a phase should still be visible.
 */
export function sequenceFor(moduleType: ModuleTypeId, currentPhase: string | null | undefined): string[] {
  const base = PHASE_SEQUENCES[moduleType] ?? PHASE_SEQUENCES.dsa;
  if (!currentPhase || currentPhase === 'PENDING' || base.includes(currentPhase)) return base;
  return [...base.slice(0, -1), currentPhase, base[base.length - 1]];
}

/** The editor language a module's artifact defaults to. */
export function defaultLanguageFor(moduleType: ModuleTypeId): string {
  switch (moduleType) {
    case 'hld':
      return 'markdown';
    case 'cs_fundamentals':
      return 'markdown';
    default:
      return 'java';
  }
}

/** What the artifact pane is called for a given module — the artifact is not always code. */
export function artifactLabelFor(moduleType: ModuleTypeId): string {
  switch (moduleType) {
    case 'hld':
      return 'System design graph';
    case 'lld':
      return 'Class model';
    case 'cs_fundamentals':
      return 'Scratchpad';
    default:
      return 'Code';
  }
}
