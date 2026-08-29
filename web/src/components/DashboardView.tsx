import { useEffect, useMemo, useState } from 'react';
import {
  ApiError,
  describeError,
  getReadiness,
  getSessionReport,
  getTrend,
  listProfiles,
  listSessions,
} from '../api/client';
import type {
  CompanyProfile,
  InterviewSession,
  ModuleTypeId,
  ReadinessResult,
  SessionReport,
  TrendResponse,
} from '../api/types';
import { formatIsoTime, titleCase } from '../lib/format';
import { MODULE_LABELS, isModuleType } from '../lib/phases';

interface Props {
  onExit: () => void;
  onReplay: (roundId: number) => void;
  onOpenSettings: () => void;
}

type InitialState =
  | { phase: 'loading' }
  | { phase: 'ready'; profiles: CompanyProfile[]; sessions: InterviewSession[] }
  | { phase: 'error'; message: string; offline: boolean };

function scoreLabel(score: number): string {
  return `${score.toFixed(1)} / 5`;
}

export function DashboardView({ onExit, onReplay, onOpenSettings }: Props) {
  const [initial, setInitial] = useState<InitialState>({ phase: 'loading' });
  const [profileId, setProfileId] = useState<string>('');
  const [readiness, setReadiness] = useState<ReadinessResult | null>(null);
  const [readinessError, setReadinessError] = useState<string | null>(null);
  const [trends, setTrends] = useState<Record<string, TrendResponse>>({});
  const [selectedSessionId, setSelectedSessionId] = useState<number | null>(null);
  const [report, setReport] = useState<SessionReport | null>(null);
  const [reportError, setReportError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([listProfiles(), listSessions()])
      .then(([profiles, sessions]) => {
        if (cancelled) return;
        const safeProfiles = Array.isArray(profiles) ? profiles : [];
        const safeSessions = Array.isArray(sessions) ? sessions : [];
        setInitial({ phase: 'ready', profiles: safeProfiles, sessions: safeSessions });
        setProfileId((current) => current || safeProfiles[0]?.id || '');
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setInitial({
          phase: 'error',
          message: describeError(err),
          offline: err instanceof ApiError && err.kind === 'offline',
        });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const profiles = initial.phase === 'ready' ? initial.profiles : [];
  const sessions = initial.phase === 'ready' ? initial.sessions : [];
  const profile = useMemo(() => profiles.find((candidate) => candidate.id === profileId) ?? null, [profiles, profileId]);
  const profileSessions = useMemo(
    () => sessions.filter((session) => session.companyProfileId === profileId),
    [sessions, profileId],
  );
  const modules = useMemo(() => {
    const fromProfile = Object.keys(profile?.emphasis ?? {});
    const fromSessions = profileSessions.flatMap((session) => session.rounds?.map((round) => round.moduleType) ?? []);
    return [...new Set([...fromProfile, ...fromSessions])].filter(isModuleType);
  }, [profile, profileSessions]);

  useEffect(() => {
    let cancelled = false;
    setReadiness(null);
    setReadinessError(null);
    setTrends({});
    setSelectedSessionId(null);
    setReport(null);
    setReportError(null);
    if (!profileId) return;

    getReadiness(profileId)
      .then((result) => {
        if (!cancelled) setReadiness(result);
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          // A 404 here means the profile simply has no scored snapshots yet, not that the
          // feature is unavailable. Keep the empty state encouraging and accurate.
          setReadinessError(err instanceof ApiError && err.status === 404 ? null : describeError(err));
        }
      });

    Promise.all(
      modules.map(async (module) => {
        try {
          return [module, await getTrend(profileId, module)] as const;
        } catch {
          return null;
        }
      }),
    ).then((results) => {
      if (cancelled) return;
      const successful = results.filter(
        (result): result is readonly [ModuleTypeId, TrendResponse] => result !== null,
      );
      setTrends(Object.fromEntries(successful));
    });

    const latestCompleted = profileSessions.find((session) => session.status === 'COMPLETED');
    setSelectedSessionId(latestCompleted?.id ?? null);
    return () => {
      cancelled = true;
    };
  }, [profileId, modules, profileSessions]);

  useEffect(() => {
    let cancelled = false;
    setReport(null);
    setReportError(null);
    if (selectedSessionId === null) return;
    getSessionReport(selectedSessionId)
      .then((value) => {
        if (!cancelled) setReport(value);
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setReportError(err instanceof ApiError && err.status === 404
            ? 'The report is not available yet. Evaluation may still be finishing.'
            : describeError(err));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [selectedSessionId]);

  return (
    <div className="dashboard">
      <header className="dashboard-head">
        <div className="dashboard-brand">
          <button type="button" className="btn btn-ghost" onClick={onExit}>← Back</button>
          <div>
            <h1>Progress</h1>
            <p className="setup-tagline">Readiness is a trend indicator, not a prediction.</p>
          </div>
        </div>
        <button type="button" className="btn btn-ghost" onClick={onOpenSettings}>Settings</button>
      </header>

      {initial.phase === 'loading' && (
        <main className="dashboard-body" aria-busy="true">
          <div className="dashboard-grid skeleton-grid">
            <div className="skeleton-card" /><div className="skeleton-card" /><div className="skeleton-card" />
          </div>
        </main>
      )}

      {initial.phase === 'error' && (
        <main className="dashboard-body">
          <div className="notice notice-error">
            <p className="notice-title">{initial.offline ? 'Backend is not running.' : 'Could not load progress.'}</p>
            <p className="notice-body">{initial.message}</p>
          </div>
        </main>
      )}

      {initial.phase === 'ready' && (
        <main className="dashboard-body">
          <div className="dashboard-filter">
            <label className="field" htmlFor="dashboard-company">
              <span className="field-label">Company</span>
              <select id="dashboard-company" className="select" value={profileId} onChange={(event) => setProfileId(event.target.value)}>
                {profiles.map((candidate) => <option key={candidate.id} value={candidate.id}>{candidate.displayName ?? candidate.id}</option>)}
              </select>
            </label>
            {profile?.calibration?.confidence === 'seeded-unverified' && (
              <p className="dashboard-calibration">Profile calibration is seeded and unverified.</p>
            )}
          </div>

          <section className="dashboard-grid" aria-label="Readiness overview">
            <article className="metric-card metric-primary">
              <span className="metric-label">Current readiness</span>
              {readiness ? <><strong>{titleCase(readiness.band)}</strong><span>{scoreLabel(readiness.overallScore)} · {readiness.confidence} confidence</span></> :
                <><strong>Not enough data</strong><span>{readinessError ?? 'Complete a scored round to begin.'}</span></>}
            </article>
            <article className="metric-card">
              <span className="metric-label">Comparable samples</span>
              <strong>{readiness?.totalSamples ?? 0}</strong>
              <span>Only scores from the current evaluator epoch are combined.</span>
            </article>
            <article className="metric-card">
              <span className="metric-label">Sessions</span>
              <strong>{profileSessions.length}</strong>
              <span>{profileSessions.filter((session) => session.status === 'COMPLETED').length} completed</span>
            </article>
          </section>

          <section className="dashboard-section">
            <div className="section-title"><h2>Module readiness</h2><span>Scores out of 5</span></div>
            {!readiness && <div className="empty-state"><p className="empty-title">No scored rounds yet.</p><p className="empty-body">Your first completed evaluation will appear here.</p></div>}
            {readiness && (
              <div className="module-score-grid">
                {Object.entries(readiness.moduleScores).map(([module, score]) => {
                  const minimum = readiness.failingMinimums[module];
                  return <article className={`module-score${minimum != null ? ' is-gated' : ''}`} key={module}>
                    <div><h3>{isModuleType(module) ? MODULE_LABELS[module] : titleCase(module)}</h3><strong>{scoreLabel(score)}</strong></div>
                    <div className="score-track" aria-label={`${module} score ${scoreLabel(score)}`}><span style={{ width: `${Math.min(100, score * 20)}%` }} /></div>
                    <p>{minimum != null ? `Needs ${minimum.toFixed(1)} to clear its minimum.` : `${readiness.moduleSampleCounts[module] ?? 0} comparable sample(s).`}</p>
                  </article>;
                })}
              </div>
            )}
          </section>

          <section className="dashboard-section">
            <div className="section-title"><h2>Trend</h2><span>Epoch changes are marked in each series.</span></div>
            {Object.keys(trends).length === 0 && <div className="empty-state"><p className="empty-title">No trend data yet.</p><p className="empty-body">Finish two or more rounds in a module to make the direction useful.</p></div>}
            <div className="trend-grid">
              {Object.entries(trends).map(([module, trend]) => <TrendCard key={module} module={module} trend={trend} />)}
            </div>
          </section>

          <section className="dashboard-lower">
            <div className="dashboard-section session-history">
              <div className="section-title"><h2>Session history</h2><span>{profileSessions.length} sessions</span></div>
              {profileSessions.length === 0 && <div className="empty-state"><p className="empty-title">No sessions for this company.</p><p className="empty-body">Start a practice round from the setup screen.</p></div>}
              <div className="session-list">
                {profileSessions.map((session) => <article className={`session-card${selectedSessionId === session.id ? ' is-selected' : ''}`} key={session.id}>
                  <button type="button" className="session-select" onClick={() => setSelectedSessionId(session.id)}>
                    <span><strong>{session.mode === 'FULL_LOOP' ? 'Full loop' : 'Practice round'}</strong><small>{formatIsoTime(session.startedAt)}</small></span>
                    <span className={`status-pill status-${session.status.toLowerCase()}`}>{titleCase(session.status)}</span>
                  </button>
                  <div className="session-rounds">
                    {(session.rounds ?? []).map((round) => <button type="button" className="link-btn" key={round.id} onClick={() => onReplay(round.id)}>Replay {round.ordinal}</button>)}
                  </div>
                </article>)}
              </div>
            </div>

            <div className="dashboard-section report-panel">
              <div className="section-title"><h2>Session report</h2>{selectedSessionId != null && <span>Session #{selectedSessionId}</span>}</div>
              {selectedSessionId === null && <div className="empty-state"><p className="empty-title">Choose a completed session.</p><p className="empty-body">Its aggregate feedback will appear here.</p></div>}
              {selectedSessionId != null && reportError && <div className="notice notice-warn compact"><p className="notice-body">Report unavailable: {reportError}</p></div>}
              {report && <><div className="report-band"><span>Overall band</span><strong>{titleCase(report.overallBand)}</strong></div><div className="report-scores">{Object.entries(report.perModule).map(([module, score]) => <span key={module}>{isModuleType(module) ? MODULE_LABELS[module] : titleCase(module)} <strong>{scoreLabel(score)}</strong></span>)}</div><p className="report-narrative">{report.narrativeMd || 'No narrative was recorded.'}</p></>}
            </div>
          </section>
        </main>
      )}
    </div>
  );
}

function TrendCard({ module, trend }: { module: string; trend: TrendResponse }) {
  const points = [...trend.points].reverse();
  return <article className="trend-card">
    <div className="trend-card-head"><h3>{isModuleType(module) ? MODULE_LABELS[module] : titleCase(module)}</h3><span>{points.length} sample(s)</span></div>
    <div className="trend-bars" aria-label={`${module} historical scores`}>
      {points.map((point, index) => <div className="trend-bar-wrap" key={`${point.takenAt}-${index}`} title={`${scoreLabel(point.score)} · ${formatIsoTime(point.takenAt)} · epoch ${point.comparabilityEpoch}`}>
        <span className="trend-bar" style={{ height: `${Math.max(8, point.score * 20)}%` }} />
        {index > 0 && points[index - 1].comparabilityEpoch !== point.comparabilityEpoch && <i className="epoch-break" aria-label="Evaluator epoch changed" />}
      </div>)}
    </div>
    <p>Latest {scoreLabel(points.at(-1)?.score ?? 0)} · epoch {trend.comparabilityEpoch}</p>
  </article>;
}
