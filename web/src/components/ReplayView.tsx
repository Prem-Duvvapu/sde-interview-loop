import { useEffect, useState } from 'react';
import { ApiError, describeError, getArtifacts, getTranscript } from '../api/client';
import type { ArtifactSnapshot, TranscriptTurn } from '../api/types';
import { formatIsoTime } from '../lib/format';

interface Props {
  roundId: number;
  onExit: () => void;
}

type State =
  | { phase: 'loading' }
  | { phase: 'ready'; turns: TranscriptTurn[]; artifacts: ArtifactSnapshot[]; artifactError: string | null }
  | { phase: 'error'; message: string; offline: boolean };

export function ReplayView({ roundId, onExit }: Props) {
  const [state, setState] = useState<State>({ phase: 'loading' });
  const [selectedArtifact, setSelectedArtifact] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setState({ phase: 'loading' });
    setSelectedArtifact(0);

    (async () => {
      try {
        const turns = await getTranscript(roundId);
        // Artifacts are secondary: a round with no code must still replay.
        let artifacts: ArtifactSnapshot[] = [];
        let artifactError: string | null = null;
        try {
          artifacts = await getArtifacts(roundId);
        } catch (err: unknown) {
          artifactError = describeError(err);
        }
        if (cancelled) return;
        setState({
          phase: 'ready',
          turns: Array.isArray(turns) ? turns : [],
          artifacts: Array.isArray(artifacts) ? artifacts : [],
          artifactError,
        });
      } catch (err: unknown) {
        if (cancelled) return;
        setState({
          phase: 'error',
          message: describeError(err),
          offline: err instanceof ApiError && err.kind === 'offline',
        });
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [roundId]);

  const artifacts = state.phase === 'ready' ? state.artifacts : [];
  const current = artifacts[selectedArtifact];

  return (
    <div className="app-shell">
      <header className="topbar">
        <button type="button" className="btn btn-ghost" onClick={onExit}>
          ← Back
        </button>
        <div className="topbar-title">
          <span className="topbar-primary">Replay</span>
          <span className="topbar-secondary">round #{roundId}</span>
        </div>
      </header>

      {state.phase === 'loading' && (
        <div className="pane-row">
          <div className="skeleton-list pad" aria-busy="true">
            {Array.from({ length: 5 }).map((_, i) => (
              <div className="skeleton-row" key={i} />
            ))}
          </div>
        </div>
      )}

      {state.phase === 'error' && (
        <div className="pane-row">
          <div className="notice notice-error self-center">
            <p className="notice-title">
              {state.offline ? 'Backend is not running.' : `Could not load round #${roundId}.`}
            </p>
            <p className="notice-body">{state.offline ? 'Start the app on localhost:8080 and try again.' : state.message}</p>
          </div>
        </div>
      )}

      {state.phase === 'ready' && (
        <div className="pane-row">
          <section className="pane transcript-pane" aria-label="Replayed transcript">
            <div className="pane-head">
              <h2>Transcript</h2>
              <span className="pane-sub">{state.turns.length} turns</span>
            </div>
            <div className="transcript-scroll">
              {state.turns.length === 0 && (
                <div className="empty-state">
                  <p className="empty-title">No turns recorded.</p>
                  <p className="empty-body">
                    Either the round has not run, or it ended before the first turn was persisted.
                  </p>
                </div>
              )}
              {state.turns.map((turn) => (
                <article className={`turn turn-${turn.role.toLowerCase()}`} key={turn.id}>
                  <header className="turn-head">
                    <span className="turn-who">{turn.role === 'CANDIDATE' ? 'You' : turn.role.toLowerCase()}</span>
                    <span className="turn-badge">#{turn.ordinal}</span>
                    {turn.latencyMs != null && <span className="turn-badge">{turn.latencyMs}ms</span>}
                    <time className="turn-time">{formatIsoTime(turn.createdAt)}</time>
                  </header>
                  <div className="prose">
                    <span className="prose-text">{turn.content}</span>
                  </div>
                </article>
              ))}
            </div>
          </section>

          <section className="pane editor-pane" aria-label="Artifact snapshots">
            <div className="pane-head">
              <h2>Artifacts</h2>
              <div className="editor-tools">
                {artifacts.length > 1 && (
                  <>
                    <label className="visually-hidden" htmlFor="artifact-select">
                      Artifact snapshot
                    </label>
                    <select
                      id="artifact-select"
                      className="select-sm"
                      value={selectedArtifact}
                      onChange={(e) => setSelectedArtifact(Number(e.target.value))}
                    >
                      {artifacts.map((a, i) => (
                        <option key={a.id} value={i}>
                          #{i + 1} · {a.kind} · {formatIsoTime(a.createdAt)}
                        </option>
                      ))}
                    </select>
                  </>
                )}
                <span className="pane-sub">{artifacts.length} snapshots</span>
              </div>
            </div>
            <div className="replay-artifact">
              {state.artifactError && (
                <div className="notice notice-warn compact">
                  <p className="notice-body">Artifacts unavailable: {state.artifactError}</p>
                </div>
              )}
              {!state.artifactError && artifacts.length === 0 && (
                <div className="empty-state">
                  <p className="empty-title">No artifact snapshots.</p>
                  <p className="empty-body">This round produced no code or diagram buffer.</p>
                </div>
              )}
              {current && (
                <pre className="artifact-code">
                  <code>{current.payload}</code>
                </pre>
              )}
            </div>
          </section>
        </div>
      )}
    </div>
  );
}
