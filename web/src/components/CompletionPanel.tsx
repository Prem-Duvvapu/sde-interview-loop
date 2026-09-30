import type { Completion } from '../lib/completion';
import { useTicker } from '../lib/hooks';

interface Props {
  completion: Completion;
  isFullLoop: boolean;
  onReview: (roundId: number) => void;
  onExit: () => void;
}

/** Wire values from `ReadinessBand.wireValue()`. Labelled as mock outcomes, not hiring facts. */
const BAND_LABEL: Record<string, string> = {
  'no-hire': 'Below the SDE-2 bar',
  'lean-hire': 'Close to the bar',
  hire: 'At the SDE-2 bar',
  'strong-hire': 'Clearly above the bar',
};

function bandLabel(band: string | null): string {
  if (!band) return 'Scored';
  return BAND_LABEL[band.toLowerCase()] ?? band.replace(/[-_]/g, ' ');
}

/**
 * Replaces the composer once a round stops accepting answers. Always says what is happening
 * and always offers a way forward — including when scoring failed, because the transcript is
 * persisted independently of the evaluator.
 */
export function CompletionPanel({ completion, isFullLoop, onReview, onExit }: Props) {
  const evaluating = completion.state === 'evaluating';
  const now = useTicker(1000, evaluating);

  switch (completion.state) {
    case 'active':
      return null;

    case 'evaluating': {
      const seconds = Math.max(0, Math.floor((now - completion.since) / 1000));
      return (
        <section className="completion" role="status" aria-live="polite" aria-busy="true">
          <div className="completion-head">
            <span className="spinner" aria-hidden="true" />
            <h2 className="completion-title">Scoring this round…</h2>
          </div>
          <p className="completion-body">
            The evaluator reads the whole transcript against the rubric. This usually takes under a minute
            and can take two with a slow provider. Your answers are already saved.
            {isFullLoop && ' The next round starts automatically when scoring finishes.'}
          </p>
          <p className="completion-meta">{seconds}s elapsed</p>
        </section>
      );
    }

    case 'evaluated': {
      const { evaluation, roundId } = completion;
      return (
        <section className="completion" role="status" aria-live="polite">
          <div className="completion-head">
            <h2 className="completion-title">Round scored: {bandLabel(evaluation.readinessBand)}</h2>
          </div>
          <div className="completion-columns">
            {evaluation.strengths.length > 0 && (
              <div>
                <h3 className="completion-sub">What went well</h3>
                <ul>{evaluation.strengths.slice(0, 3).map((item) => <li key={item}>{item}</li>)}</ul>
              </div>
            )}
            {evaluation.gaps.length > 0 && (
              <div>
                <h3 className="completion-sub">Work on next</h3>
                <ul>{evaluation.gaps.slice(0, 3).map((item) => <li key={item}>{item}</li>)}</ul>
              </div>
            )}
          </div>
          <p className="completion-meta">
            Scored by {evaluation.evaluatorProvider} · {evaluation.evaluatorModel} · rubric {evaluation.rubricVersion} · epoch {evaluation.comparabilityEpoch}
          </p>
          <div className="completion-actions">
            <button type="button" className="btn btn-primary" onClick={() => onReview(roundId)}>Review transcript</button>
            <button type="button" className="btn btn-ghost" onClick={onExit}>Back to practice</button>
          </div>
        </section>
      );
    }

    case 'evaluation_unavailable':
      return (
        <section className="completion tone-warn" role="status" aria-live="polite">
          <div className="completion-head">
            <h2 className="completion-title">Round finished — not scored</h2>
          </div>
          <p className="completion-body">{completion.message}</p>
          <div className="completion-actions">
            <button type="button" className="btn btn-primary" onClick={() => onReview(completion.roundId)}>Review transcript</button>
            <button type="button" className="btn btn-ghost" onClick={onExit}>Back to practice</button>
          </div>
        </section>
      );

    case 'next_round':
      return (
        <section className="completion" role="status" aria-live="polite" aria-busy="true">
          <div className="completion-head">
            <span className="spinner" aria-hidden="true" />
            <h2 className="completion-title">Preparing round {completion.ordinal}…</h2>
          </div>
          <p className="completion-body">The next interviewer is getting a private handoff from this round.</p>
        </section>
      );
  }
}
