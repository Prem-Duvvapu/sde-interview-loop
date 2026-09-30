import type { TranscriptTurn } from '../api/types';
import { nextId, type ChatItem } from './chat';

/**
 * Pure transcript updates for streamed interviewer turns.
 *
 * A turn's prose can be interrupted by system notices (phase changes, hint notices) that are
 * appended between text deltas. Earlier versions only looked at the *last* item, so a delta
 * after a notice started a second bubble and `turn_complete` left the first one marked as
 * streaming forever (a caret that never stopped). Deltas now continue the turn's open bubble
 * wherever it sits, and completion closes every open bubble.
 */

type Interviewer = Extract<ChatItem, { kind: 'interviewer' }>;

function openInterviewerIndex(items: ChatItem[]): number {
  for (let i = items.length - 1; i >= 0; i -= 1) {
    const item = items[i];
    // A candidate turn always closes the previous interviewer turn.
    if (item.kind === 'candidate') return -1;
    if (item.kind === 'interviewer') return item.streaming ? i : -1;
  }
  return -1;
}

export function appendInterviewerDelta(items: ChatItem[], text: string, at: number = Date.now()): ChatItem[] {
  if (!text) return items;
  const index = openInterviewerIndex(items);
  if (index === -1) {
    return [...items, { id: nextId('int'), kind: 'interviewer', at, text, streaming: true }];
  }
  const open = items[index] as Interviewer;
  const next = items.slice();
  next[index] = { ...open, text: open.text + text };
  return next;
}

/** Marks every still-streaming interviewer bubble complete. Returns the same array if none were. */
export function finalizeStreaming(items: ChatItem[]): ChatItem[] {
  let changed = false;
  const next = items.map((item) => {
    if (item.kind === 'interviewer' && item.streaming) {
      changed = true;
      return { ...item, streaming: false };
    }
    return item;
  });
  return changed ? next : items;
}

export function hasStreamingTurn(items: ChatItem[]): boolean {
  return items.some((item) => item.kind === 'interviewer' && item.streaming);
}

/**
 * Rebuilds the visible conversation from persisted turns (reload / reconnect). Only the
 * conversation is restored; transient notices are not persisted server-side.
 */
export function itemsFromTranscript(turns: TranscriptTurn[]): ChatItem[] {
  const out: ChatItem[] = [];
  for (const turn of turns) {
    const at = Date.parse(turn.createdAt ?? '') || Date.now();
    if (turn.role === 'INTERVIEWER') {
      out.push({ id: `turn-${turn.id}`, kind: 'interviewer', at, text: turn.content ?? '', streaming: false });
    } else if (turn.role === 'CANDIDATE') {
      out.push({ id: `turn-${turn.id}`, kind: 'candidate', at, text: turn.content ?? '', artifactChars: 0 });
    }
  }
  return out;
}

/** Counts conversation turns (ignores local notices) — used to detect a server-side gap. */
export function conversationLength(items: ChatItem[]): number {
  return items.filter((item) => item.kind === 'interviewer' || item.kind === 'candidate').length;
}

/** Friendly, non-evaluative description of a control call, or null to show nothing. */
export function describeControlCall(name: string, args: Record<string, unknown>): string | null {
  switch (name) {
    case 'set_hint_level': {
      const level = typeof args.level === 'number' ? args.level : null;
      return level === null ? 'The interviewer offered more guidance.' : `The interviewer offered more guidance (hint level ${level}).`;
    }
    case 'end_round':
      return 'The interviewer is wrapping up the round.';
    // advance_phase is announced by the phase_advanced frame only when the backend accepts it;
    // record_signal is private and never sent by the backend.
    default:
      return null;
  }
}
