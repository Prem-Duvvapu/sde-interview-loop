import { useEffect, useMemo, useState } from 'react';
import { describeError, listProfiles, ApiError } from '../api/client';
import type { CompanyProfile, ModuleTypeId, SessionModeId } from '../api/types';
import { MODULE_LABELS, RUNNABLE_MODULES, isModuleType } from '../lib/phases';
import { titleCase } from '../lib/format';

interface Props {
  onStart: (opts: {
    profile: CompanyProfile;
    mode: SessionModeId;
    moduleType: ModuleTypeId;
    difficultyTarget: string;
  }) => void;
  onReplay: (roundId: number) => void;
  onOpenDashboard: () => void;
  onOpenSettings: () => void;
  starting: boolean;
  startError: string | null;
}

type LoadState =
  | { phase: 'loading' }
  | { phase: 'ready'; profiles: CompanyProfile[] }
  | { phase: 'error'; message: string; offline: boolean };

const DIFFICULTIES = ['easy', 'medium', 'medium-hard', 'hard'];

export function SetupView({ onStart, onReplay, onOpenDashboard, onOpenSettings, starting, startError }: Props) {
  const [load, setLoad] = useState<LoadState>({ phase: 'loading' });
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [mode, setMode] = useState<SessionModeId>('single_module');
  const [moduleType, setModuleType] = useState<ModuleTypeId>('dsa');
  const [difficulty, setDifficulty] = useState('medium');
  const [replayId, setReplayId] = useState('');
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoad({ phase: 'loading' });
    listProfiles()
      .then((profiles) => {
        if (cancelled) return;
        const list = Array.isArray(profiles) ? profiles : [];
        setLoad({ phase: 'ready', profiles: list });
        setSelectedId((prev) => prev ?? list[0]?.id ?? null);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setLoad({
          phase: 'error',
          message: describeError(err),
          offline: err instanceof ApiError && err.kind === 'offline',
        });
      });
    return () => {
      cancelled = true;
    };
  }, [reloadKey]);

  const profiles = load.phase === 'ready' ? load.profiles : [];
  const selected = useMemo(
    () => profiles.find((p) => p.id === selectedId) ?? null,
    [profiles, selectedId],
  );

  const rounds = selected?.loop?.rounds ?? [];

  // Default the module and difficulty to the profile's first runnable round.
  useEffect(() => {
    if (!selected) return;
    const firstEnabled = rounds.find((r) => r.enabled_in_v1 !== false && isModuleType(r.module));
    if (firstEnabled && isModuleType(firstEnabled.module)) {
      setModuleType(firstEnabled.module);
      if (firstEnabled.difficulty_target) setDifficulty(firstEnabled.difficulty_target);
    }
    // Keyed on the profile only — changing the module by hand must stick.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selected?.id]);

  const canStart = selected !== null && !starting;

  return (
    <div className="setup">
      <header className="setup-head">
        <div>
          <h1>SDE Interview Loop</h1>
          <p className="setup-tagline">Company-calibrated mock interviews · SDE-2 backend</p>
        </div>
        <div className="setup-actions">
          <button type="button" className="btn btn-ghost" onClick={onOpenDashboard}>
            Progress
          </button>
          <button type="button" className="btn btn-ghost" onClick={onOpenSettings}>
            Settings
          </button>
        </div>
      </header>

      {load.phase === 'loading' && (
        <div className="setup-body">
          <div className="skeleton-list" aria-busy="true" aria-label="Loading company profiles">
            {Array.from({ length: 6 }).map((_, i) => (
              <div className="skeleton-row" key={i} />
            ))}
          </div>
        </div>
      )}

      {load.phase === 'error' && (
        <div className="setup-body">
          <div className="notice notice-error">
            <p className="notice-title">
              {load.offline ? 'Backend is not running.' : 'Could not load company profiles.'}
            </p>
            <p className="notice-body">
              {load.offline ? (
                <>
                  Start the Spring Boot app on <code>localhost:8123</code>, then retry. The dev server
                  proxies <code>/api</code> and <code>/ws</code> to it.
                </>
              ) : (
                load.message
              )}
            </p>
            <button type="button" className="btn btn-ghost" onClick={() => setReloadKey((k) => k + 1)}>
              Retry
            </button>
          </div>
        </div>
      )}

      {load.phase === 'ready' && (
        <div className="setup-body setup-grid">
          <aside className="profile-list" aria-label="Company profiles">
            <div className="list-head">
              <span>{profiles.length} profiles</span>
              <button type="button" className="link-btn" onClick={() => setReloadKey((k) => k + 1)}>
                Refresh
              </button>
            </div>
            {profiles.length === 0 && <p className="muted pad">No profiles returned by the backend.</p>}
            <ul>
              {profiles.map((p) => (
                <li key={p.id}>
                  <button
                    type="button"
                    className={`profile-item${p.id === selectedId ? ' is-selected' : ''}`}
                    onClick={() => setSelectedId(p.id)}
                    aria-pressed={p.id === selectedId}
                  >
                    <span className="profile-name">{p.displayName ?? p.id}</span>
                    <span className="profile-meta">
                      <code>{p.id}</code>
                      {p.targetRole?.levelCode && <span className="chip chip-level">{p.targetRole.levelCode}</span>}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </aside>

          <main className="profile-detail">
            {!selected && <p className="muted pad">Select a company to see its loop.</p>}
            {selected && (
              <>
                <div className="detail-head">
                  <h2>{selected.displayName ?? selected.id}</h2>
                  <div className="detail-badges">
                    {selected.targetRole?.title && <span className="chip">{selected.targetRole.title}</span>}
                    {selected.targetRole?.levelCode && (
                      <span className="chip chip-level">{selected.targetRole.levelCode}</span>
                    )}
                    {selected.difficulty && <span className="chip">{titleCase(selected.difficulty)}</span>}
                  </div>
                </div>

                {selected.calibration?.confidence && (
                  <p className="calibration-note">
                    <span className={`chip chip-cal cal-${selected.calibration.confidence}`}>
                      {titleCase(selected.calibration.confidence)}
                    </span>
                    {selected.calibration.confidence === 'seeded-unverified'
                      ? ' — these numbers are plausible defaults, not verified interview data.'
                      : ` — last updated ${selected.calibration.lastUpdated ?? 'unknown'}.`}
                  </p>
                )}

                <section className="detail-section">
                  <h3>
                    Loop
                    {selected.loop?.totalWallClockMin ? (
                      <span className="section-note">{selected.loop.totalWallClockMin} min total</span>
                    ) : null}
                  </h3>
                  <table className="round-table">
                    <thead>
                      <tr>
                        <th scope="col">#</th>
                        <th scope="col">Module</th>
                        <th scope="col">Round</th>
                        <th scope="col">Duration</th>
                        <th scope="col">Target</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rounds.map((r) => {
                        const disabled = r.enabled_in_v1 === false;
                        return (
                          <tr key={r.ordinal} className={disabled ? 'is-disabled' : ''}>
                            <td className="num">{r.ordinal}</td>
                            <td>
                              <code>{r.module}</code>
                            </td>
                            <td>
                              {r.name ?? '—'}
                              {disabled && <span className="chip chip-off">not in v1</span>}
                            </td>
                            <td className="num">{r.duration_min} min</td>
                            <td>{r.difficulty_target ?? '—'}</td>
                          </tr>
                        );
                      })}
                      {rounds.length === 0 && (
                        <tr>
                          <td colSpan={5} className="muted">
                            This profile declares no rounds.
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </section>

                <section className="detail-section">
                  <h3>Start a session</h3>
                  <div className="start-controls">
                    <div className="field">
                      <span className="field-label">Mode</span>
                      <div className="segmented" role="group" aria-label="Session mode">
                        <button
                          type="button"
                          className={mode === 'single_module' ? 'is-active' : ''}
                          onClick={() => setMode('single_module')}
                        >
                          Single module
                        </button>
                        <button
                          type="button"
                          className={mode === 'full_loop' ? 'is-active' : ''}
                          onClick={() => setMode('full_loop')}
                        >
                          Full loop
                        </button>
                      </div>
                    </div>

                    <div className="field">
                      <label className="field-label" htmlFor="module-select">
                        Module
                      </label>
                      <select
                        id="module-select"
                        className="select"
                        value={moduleType}
                        onChange={(e) => setModuleType(e.target.value as ModuleTypeId)}
                        disabled={mode === 'full_loop'}
                      >
                        {RUNNABLE_MODULES.map((m) => (
                          <option key={m} value={m}>
                            {MODULE_LABELS[m]}
                          </option>
                        ))}
                      </select>
                    </div>

                    <div className="field">
                      <label className="field-label" htmlFor="difficulty-select">
                        Difficulty
                      </label>
                      <select
                        id="difficulty-select"
                        className="select"
                        value={difficulty}
                        onChange={(e) => setDifficulty(e.target.value)}
                        disabled={mode === 'full_loop'}
                      >
                        {DIFFICULTIES.map((d) => (
                          <option key={d} value={d}>
                            {d}
                          </option>
                        ))}
                      </select>
                    </div>

                    <button
                      type="button"
                      className="btn btn-primary btn-lg"
                      disabled={!canStart}
                      onClick={() =>
                        selected &&
                        onStart({ profile: selected, mode, moduleType, difficultyTarget: difficulty })
                      }
                    >
                      {starting ? 'Starting…' : 'Start round'}
                    </button>
                  </div>
                  {mode === 'full_loop' && (
                    <p className="muted small">
                      Full-loop chaining lands in Phase 6. The backend creates every round now; this client
                      opens the first one.
                    </p>
                  )}
                  {startError && (
                    <div className="notice notice-error compact">
                      <p className="notice-body">{startError}</p>
                    </div>
                  )}
                </section>

                <section className="detail-section">
                  <h3>Replay a finished round</h3>
                  <form
                    className="replay-form"
                    onSubmit={(e) => {
                      e.preventDefault();
                      const id = Number(replayId);
                      if (Number.isFinite(id) && id > 0) onReplay(id);
                    }}
                  >
                    <label className="visually-hidden" htmlFor="replay-round-id">
                      Round id
                    </label>
                    <input
                      id="replay-round-id"
                      className="input"
                      inputMode="numeric"
                      placeholder="Round id"
                      value={replayId}
                      onChange={(e) => setReplayId(e.target.value.replace(/[^\d]/g, ''))}
                    />
                    <button type="submit" className="btn btn-ghost" disabled={replayId === ''}>
                      Open replay
                    </button>
                  </form>
                </section>
              </>
            )}
          </main>
        </div>
      )}
    </div>
  );
}
