import { describe, expect, it } from 'vitest';
import type { ChatItem } from './chat';
import { appendInterviewerDelta, conversationLength, describeControlCall, finalizeStreaming, hasStreamingTurn, itemsFromTranscript } from './transcript';

const system = (text: string): ChatItem => ({ id: `s-${text}`, kind: 'system', at: 0, text, tone: 'info' });
const candidate = (text: string): ChatItem => ({ id: `c-${text}`, kind: 'candidate', at: 0, text, artifactChars: 0 });

describe('streamed interviewer turns', () => {
  it('continues the open bubble when a notice is interleaved between deltas', () => {
    let items: ChatItem[] = [];
    items = appendInterviewerDelta(items, 'Good question. ');
    items = [...items, system('Phase → clarifying')];
    items = appendInterviewerDelta(items, 'What range can values take?');

    const bubbles = items.filter((i) => i.kind === 'interviewer');
    expect(bubbles).toHaveLength(1);
    expect(bubbles[0]).toMatchObject({ text: 'Good question. What range can values take?', streaming: true });
  });

  it('regression: completion closes every open bubble, not only the last item', () => {
    // Previously turn_complete looked only at items[items.length - 1]; with a notice last,
    // the interviewer bubble kept its streaming caret forever.
    let items: ChatItem[] = appendInterviewerDelta([], 'Thinking about it…');
    items = [...items, system('The interviewer offered more guidance.')];
    items = finalizeStreaming(items);
    expect(hasStreamingTurn(items)).toBe(false);
  });

  it('a candidate turn starts a new interviewer bubble', () => {
    let items: ChatItem[] = appendInterviewerDelta([], 'First reply');
    items = finalizeStreaming(items);
    items = [...items, candidate('my answer')];
    items = appendInterviewerDelta(items, 'Second reply');
    expect(items.filter((i) => i.kind === 'interviewer')).toHaveLength(2);
  });

  it('does not reopen a finished bubble', () => {
    let items = finalizeStreaming(appendInterviewerDelta([], 'Done.'));
    items = appendInterviewerDelta(items, 'New turn');
    expect(items.filter((i) => i.kind === 'interviewer')).toHaveLength(2);
  });

  it('finalize returns the same array when nothing is streaming (no needless re-render)', () => {
    const items: ChatItem[] = [candidate('x')];
    expect(finalizeStreaming(items)).toBe(items);
  });

  it('ignores empty deltas', () => {
    const items: ChatItem[] = [];
    expect(appendInterviewerDelta(items, '')).toBe(items);
  });
});

describe('transcript rehydration', () => {
  it('rebuilds conversation turns and drops system turns', () => {
    const items = itemsFromTranscript([
      { id: 1, ordinal: 1, role: 'INTERVIEWER', content: 'Brief', createdAt: '2026-09-30T10:00:00Z' },
      { id: 2, ordinal: 2, role: 'CANDIDATE', content: 'Answer', createdAt: null },
      { id: 3, ordinal: 3, role: 'SYSTEM', content: 'internal' },
    ]);
    expect(items.map((i) => i.kind)).toEqual(['interviewer', 'candidate']);
    expect(conversationLength([...items, system('local notice')])).toBe(2);
  });
});

describe('control-call notices', () => {
  it('never describes private or evaluative calls', () => {
    expect(describeControlCall('record_signal', { score: 5, evidence: 'x' })).toBeNull();
    expect(describeControlCall('advance_phase', { target_phase: 'CODING' })).toBeNull();
    expect(describeControlCall('end_round', { reason: 'weak' })).not.toContain('weak');
    expect(describeControlCall('set_hint_level', { level: 2 })).toContain('hint level 2');
  });
});
