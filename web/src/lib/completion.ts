import type { RoundEvaluation } from '../api/types';

/**
 * What happens after a round stops accepting answers. Each state is explicit so the candidate
 * never faces an unexplained silent screen while the evaluator runs (H1 finding, 2026-09-05:
 * up to ~90s of dead air after "Round complete" with no indicator).
 *
 * `via` records who ended the round: the interviewer's `end_round` (outcome known at that
 * turn's `turn_complete`) or the candidate's End round button (outcome known when the REST
 * completion call returns). Neither path infers failure from a timeout.
 */
export type Completion =
  | { state: 'active' }
  | { state: 'evaluating'; roundId: number; via: 'interviewer' | 'candidate'; since: number }
  | { state: 'evaluated'; roundId: number; evaluation: RoundEvaluation }
  | { state: 'evaluation_unavailable'; roundId: number; message: string }
  | { state: 'next_round'; ordinal: number };
