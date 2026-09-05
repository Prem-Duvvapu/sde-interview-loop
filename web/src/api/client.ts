import type {
  AppSettings,
  ArtifactSnapshot,
  CompanyProfile,
  CreateSessionBody,
  InterviewSession,
  KeyMutationResult,
  ProviderInfo,
  ReadinessResult,
  ResumeInfo,
  SessionRound,
  SessionReport,
  TrendResponse,
  TranscriptTurn,
  VerifyResult,
} from './types';

/**
 * Every failure the UI can encounter, classified so screens can render a
 * designed state instead of dumping an exception string.
 *
 *  - `offline`      the backend is not answering at all (fetch rejected)
 *  - `unavailable`  the endpoint is not implemented yet (404 / 501 / 405)
 *  - `http`         the backend answered with an error status
 *  - `parse`        the backend answered with something that is not JSON
 */
export type ApiFailureKind = 'offline' | 'unavailable' | 'http' | 'parse';

export class ApiError extends Error {
  readonly kind: ApiFailureKind;
  readonly status: number | null;
  readonly detail: string | null;

  constructor(kind: ApiFailureKind, message: string, status: number | null = null, detail: string | null = null) {
    super(message);
    this.name = 'ApiError';
    this.kind = kind;
    this.status = status;
    this.detail = detail;
  }

  /** True when the feature simply is not built on the server yet. */
  get isUnavailable(): boolean {
    return this.kind === 'unavailable';
  }
}

export function describeError(err: unknown): string {
  if (err instanceof ApiError) {
    switch (err.kind) {
      case 'offline':
        return 'Backend not reachable on localhost:8123.';
      case 'unavailable':
        return 'This endpoint is not implemented on the backend yet.';
      case 'parse':
        return 'Backend returned a response this client could not read.';
      default:
        return err.detail?.trim() ? err.detail.trim() : err.message;
    }
  }
  if (err instanceof Error) return err.message;
  return String(err);
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let res: Response;
  try {
    res = await fetch(path, {
      ...init,
      headers: {
        Accept: 'application/json',
        // FormData sets its own multipart boundary in the Content-Type header —
        // setting it manually here would break the upload.
        ...(init?.body && !(init.body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}),
        ...init?.headers,
      },
    });
  } catch (cause) {
    throw new ApiError('offline', `Request to ${path} failed`, null, cause instanceof Error ? cause.message : null);
  }

  if (!res.ok) {
    const body = await res.text().catch(() => '');
    const kind: ApiFailureKind = res.status === 404 || res.status === 501 || res.status === 405 ? 'unavailable' : 'http';
    throw new ApiError(kind, `${res.status} ${res.statusText} on ${path}`, res.status, extractMessage(body));
  }

  if (res.status === 204) return undefined as T;

  const text = await res.text();
  if (!text.trim()) return undefined as T;
  try {
    return JSON.parse(text) as T;
  } catch {
    throw new ApiError('parse', `Non-JSON response from ${path}`, res.status, text.slice(0, 200));
  }
}

/** Spring's default error body is JSON with a `message`; fall back to raw text. */
function extractMessage(body: string): string | null {
  if (!body.trim()) return null;
  try {
    const parsed: unknown = JSON.parse(body);
    if (parsed && typeof parsed === 'object') {
      const rec = parsed as Record<string, unknown>;
      for (const key of ['message', 'error', 'detail']) {
        const v = rec[key];
        if (typeof v === 'string' && v.trim()) return v;
      }
    }
  } catch {
    /* not JSON — fall through */
  }
  return body.slice(0, 300);
}

// ---------- profiles ----------

export const listProfiles = () => request<CompanyProfile[]>('/api/profiles');

// ---------- sessions ----------

export const createSession = (body: CreateSessionBody) =>
  request<InterviewSession>('/api/sessions', { method: 'POST', body: JSON.stringify(body) });

export const listSessions = () => request<InterviewSession[]>('/api/sessions');

export const getSession = (sessionId: number) => request<InterviewSession>(`/api/sessions/${sessionId}`);

export const startRound = (sessionId: number, roundId: number) =>
  request<SessionRound>(`/api/sessions/${sessionId}/rounds/${roundId}/start`, { method: 'POST' });

export const completeRound = (sessionId: number, roundId: number) =>
  request<SessionRound>(`/api/sessions/${sessionId}/rounds/${roundId}/complete`, { method: 'POST' });

export const getSessionReport = (sessionId: number) =>
  request<SessionReport>(`/api/sessions/${sessionId}/report`);

export const getReadiness = (companyProfileId: string) =>
  request<ReadinessResult>(`/api/progress/readiness/${encodeURIComponent(companyProfileId)}`);

export const getTrend = (companyProfileId: string, module: string) =>
  request<TrendResponse>(
    `/api/progress/trend?company=${encodeURIComponent(companyProfileId)}&module=${encodeURIComponent(module)}`,
  );

// ---------- transcript / replay ----------

export const getTranscript = (roundId: number) => request<TranscriptTurn[]>(`/api/rounds/${roundId}/transcript`);

export const getArtifacts = (roundId: number) => request<ArtifactSnapshot[]>(`/api/rounds/${roundId}/artifacts`);

// ---------- providers & settings ----------

export const listProviders = () => request<ProviderInfo[]>('/api/providers');

export const putProviderKey = (id: string, apiKey: string) =>
  request<KeyMutationResult>(`/api/providers/${encodeURIComponent(id)}/key`, {
    method: 'PUT',
    body: JSON.stringify({ apiKey }),
  });

export const deleteProviderKey = (id: string) =>
  request<KeyMutationResult>(`/api/providers/${encodeURIComponent(id)}/key`, { method: 'DELETE' });

export const verifyProvider = (id: string) =>
  request<VerifyResult>(`/api/providers/${encodeURIComponent(id)}/verify`, { method: 'POST' });

export const getSettings = () => request<AppSettings>('/api/settings');

export const putInterviewerBinding = (provider: string, model: string) =>
  request<AppSettings>('/api/settings/interviewer', {
    method: 'PUT',
    body: JSON.stringify({ provider, model }),
  });

export const putEvaluatorBinding = (provider: string, model: string) =>
  request<AppSettings>('/api/settings/evaluator', {
    method: 'PUT',
    body: JSON.stringify({ provider, model, confirmEpochChange: true }),
  });

// ---------- resume ----------

export const getResume = () => request<ResumeInfo>('/api/resume');

export const uploadResume = (file: File) => {
  const form = new FormData();
  form.append('file', file);
  return request<ResumeInfo>('/api/resume', { method: 'POST', body: form });
};

export const deleteResume = () => request<void>('/api/resume', { method: 'DELETE' });
