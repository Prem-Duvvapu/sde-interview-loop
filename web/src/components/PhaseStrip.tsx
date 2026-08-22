import { phaseLabel, sequenceFor } from '../lib/phases';
import type { ModuleTypeId } from '../api/types';

interface Props {
  moduleType: ModuleTypeId;
  currentPhase: string;
  roundComplete: boolean;
}

/**
 * The signature element: the module's full phase sequence, always visible, with
 * everything before the current phase marked done. It is what makes the screen read
 * as a structured interview rather than a chat window.
 */
export function PhaseStrip({ moduleType, currentPhase, roundComplete }: Props) {
  const sequence = sequenceFor(moduleType, currentPhase);
  const activeIndex = roundComplete ? sequence.length : sequence.indexOf(currentPhase);

  return (
    <nav className="phase-strip" aria-label="Interview phases">
      <ol>
        {sequence.map((phase, i) => {
          const done = activeIndex > i;
          const active = activeIndex === i;
          return (
            <li
              key={phase}
              className={`phase-step${done ? ' is-done' : ''}${active ? ' is-active' : ''}`}
              aria-current={active ? 'step' : undefined}
            >
              <span className="phase-marker" aria-hidden="true">
                {done ? (
                  <svg viewBox="0 0 12 12" width="10" height="10">
                    <path
                      d="M1.5 6.2 4.4 9 10.5 2.8"
                      fill="none"
                      stroke="currentColor"
                      strokeWidth="1.8"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </svg>
                ) : (
                  <span className="phase-dot" />
                )}
              </span>
              <span className="phase-name">{phaseLabel(phase)}</span>
              {done && <span className="visually-hidden"> (completed)</span>}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
