/**
 * TypeScript mirrors of the JSON the Spring Boot backend actually emits.
 * Fields are optional wherever the backend can plausibly omit or null them —
 * this client is written to survive a partially-implemented backend.
 */

export type ModuleTypeId =
  | 'dsa'
  | 'lld'
  | 'hld'
  | 'cs_fundamentals'
  | 'java_deep_dive'
  | 'behavioral';

export type SessionModeId = 'single_module' | 'full_loop';

// ---------- company profiles ----------

export interface ProfileRound {
  ordinal: number;
  module: string;
  name?: string | null;
  stage?: string | null;
  duration_min: number;
  difficulty_target?: string | null;
  enabled_in_v1?: boolean | null;
  focus_tags?: string[] | null;
  notes?: string | null;
}

export interface CompanyProfile {
  id: string;
  displayName?: string | null;
  targetRole?: {
    title?: string | null;
    levelCode?: string | null;
    locationContext?: string | null;
  } | null;
  difficulty?: string | null;
  calibration?: {
    confidence?: string | null;
    lastUpdated?: string | null;
    notes?: string | null;
  } | null;
  emphasis?: Record<string, number> | null;
  loop?: {
    totalWallClockMin?: number | null;
    difficultyCurve?: string | null;
    rounds?: ProfileRound[] | null;
  } | null;
  quirks?: Array<{
    id?: string | null;
    label?: string | null;
    description?: string | null;
  }> | null;
  readiness?: {
    barBand?: string | null;
    minSessionsForConfidence?: number | null;
  } | null;
}

// ---------- sessions ----------

export type RoundStatus = 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'SKIPPED';

export interface SessionRound {
  id: number;
  ordinal: number;
  moduleType: ModuleTypeId;
  phase: string;
  status: RoundStatus;
  interviewerProvider?: string | null;
  interviewerModel?: string | null;
  questionSlug?: string | null;
  difficultyTarget?: string | null;
  plannedDurationSec?: number | null;
  actualDurationSec?: number | null;
  startedAt?: string | null;
  endedAt?: string | null;
}

export interface InterviewSession {
  id: number;
  mode: 'SINGLE_MODULE' | 'FULL_LOOP';
  companyProfileId: string;
  status: string;
  startedAt?: string | null;
  endedAt?: string | null;
  rounds?: SessionRound[] | null;
}

export interface CreateSessionBody {
  companyProfileId: string;
  mode: SessionModeId;
  moduleType?: ModuleTypeId;
  difficultyTarget?: string;
  providerId?: string;
  modelId?: string;
}

// ---------- reports and progress ----------

export interface SessionReport {
  overallBand: string | null;
  perModule: Record<string, number>;
  narrativeMd: string | null;
}

export interface ReadinessResult {
  band: string | null;
  overallScore: number;
  moduleScores: Record<string, number>;
  moduleSampleCounts: Record<string, number>;
  failingMinimums: Record<string, number>;
  confidence: 'none' | 'low' | 'confident';
  totalSamples: number;
  error?: string | null;
}

export interface TrendPoint {
  takenAt: string;
  score: number;
  sampleSize: number;
  comparabilityEpoch: number;
}

export interface TrendResponse {
  module: string;
  company: string;
  comparabilityEpoch: number;
  points: TrendPoint[];
}

// ---------- transcript / artifacts ----------

export interface TranscriptTurn {
  id: number;
  ordinal: number;
  role: 'CANDIDATE' | 'INTERVIEWER' | 'SYSTEM';
  content: string;
  contentType?: string | null;
  createdAt?: string | null;
  latencyMs?: number | null;
}

export interface ArtifactSnapshot {
  id: number;
  kind: 'CODE' | 'CLASS_MODEL' | 'DIAGRAM' | 'SCRATCH';
  language?: string | null;
  payload: string;
  createdAt?: string | null;
}

// ---------- providers & settings (Phase 1 settings contract) ----------

export interface ProviderModel {
  id: string;
  roleHint?: string | null;
  contextTokens?: number | null;
  notes?: string | null;
}

export interface ProviderCapabilities {
  streaming?: boolean;
  toolUse?: boolean;
  /** Backend currently sends an enum name ("NONE" | "IMPLICIT" | "EXPLICIT"); a boolean is also tolerated. */
  promptCaching?: string | boolean | null;
  vision?: boolean;
}

export type KeySource = 'ui' | 'env' | 'none';

export interface ProviderInfo {
  id: string;
  displayName?: string | null;
  enabled?: boolean;
  configured?: boolean;
  keySource?: KeySource;
  maskedKey?: string | null;
  capabilities?: ProviderCapabilities | null;
  models?: ProviderModel[] | null;
  /** Emitted by the current backend; preferred over deriving the floor client-side. */
  meetsInterviewerFloor?: boolean;
  meetsEvaluatorFloor?: boolean;
}

export interface KeyMutationResult {
  id: string;
  configured?: boolean;
  keySource?: KeySource;
  maskedKey?: string | null;
}

export interface VerifyResult {
  ok: boolean;
  latencyMs?: number | null;
  model?: string | null;
  message?: string | null;
}

export interface RoleBinding {
  provider?: string | null;
  model?: string | null;
}

export interface AppSettings {
  interviewer?: RoleBinding | null;
  evaluator?: (RoleBinding & { comparabilityEpoch?: number | null }) | null;
}
