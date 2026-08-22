/**
 * Frame protocol for /ws/interview.
 *
 * Encoding is defined server-side by `transport/FrameCodec.java`: every frame is a
 * flat JSON object whose `type` field discriminates it — the payload fields sit
 * alongside `type`, not nested under a `data` key.
 *
 * Inbound frame types are the full set FrameCodec can emit. Several of them
 * (tool_call, phase_advanced, usage, round_completed) are not produced by the
 * Phase 1 handler yet; they are handled here so the client needs no change when
 * the interviewer modules land.
 */

// ---------- outbound ----------

export type OutboundFrame =
  | { type: 'ping' }
  | { type: 'start_round'; roundId: number }
  | { type: 'candidate_turn'; roundId: number; text: string; artifact: string | null };

// ---------- inbound ----------

export interface PongFrame {
  type: 'pong';
}
export interface ErrorFrame {
  type: 'error';
  message: string;
}
export interface TurnAckFrame {
  type: 'turn_ack';
  roundId: number | null;
}
export interface TextDeltaFrame {
  type: 'text_delta';
  text: string;
}
export interface ToolCallFrame {
  type: 'tool_call';
  name: string;
  id: string | null;
  arguments: Record<string, unknown>;
}
export interface PhaseAdvancedFrame {
  type: 'phase_advanced';
  phase: string;
}
export interface TurnCompleteFrame {
  type: 'turn_complete';
  roundId: number | null;
}
export interface RoundStartedFrame {
  type: 'round_started';
  roundId: number | null;
}
export interface RoundCompletedFrame {
  type: 'round_completed';
  roundId: number | null;
}
export interface UsageFrame {
  type: 'usage';
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  costUsd: number;
}

export type KnownFrame =
  | PongFrame
  | ErrorFrame
  | TurnAckFrame
  | TextDeltaFrame
  | ToolCallFrame
  | PhaseAdvancedFrame
  | TurnCompleteFrame
  | RoundStartedFrame
  | RoundCompletedFrame
  | UsageFrame;

export type ParsedFrame =
  | { kind: 'known'; frame: KnownFrame }
  | { kind: 'unknown'; type: string; raw: Record<string, unknown> }
  | { kind: 'invalid'; raw: string };

// ---------- parsing ----------

const str = (v: unknown, fallback = ''): string => (typeof v === 'string' ? v : fallback);
const num = (v: unknown, fallback = 0): number => (typeof v === 'number' && Number.isFinite(v) ? v : fallback);
const optNum = (v: unknown): number | null => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const obj = (v: unknown): Record<string, unknown> =>
  v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {};

/**
 * Never throws. An unrecognised or malformed frame is reported, not fatal —
 * a backend that starts emitting a new frame type must not white-screen the UI.
 */
export function parseFrame(payload: string): ParsedFrame {
  let raw: unknown;
  try {
    raw = JSON.parse(payload);
  } catch {
    return { kind: 'invalid', raw: payload.slice(0, 400) };
  }
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) {
    return { kind: 'invalid', raw: payload.slice(0, 400) };
  }

  const rec = raw as Record<string, unknown>;
  const type = str(rec.type, '');

  switch (type) {
    case 'pong':
      return { kind: 'known', frame: { type: 'pong' } };
    case 'error':
      // FrameCodec.error() can be reached with a null message; render something either way.
      return { kind: 'known', frame: { type: 'error', message: str(rec.message, 'Unspecified server error') } };
    case 'turn_ack':
      return { kind: 'known', frame: { type: 'turn_ack', roundId: optNum(rec.roundId) } };
    case 'text_delta':
      return { kind: 'known', frame: { type: 'text_delta', text: str(rec.text) } };
    case 'tool_call':
      return {
        kind: 'known',
        frame: {
          type: 'tool_call',
          name: str(rec.name, 'unnamed_tool'),
          id: typeof rec.id === 'string' ? rec.id : null,
          arguments: obj(rec.arguments),
        },
      };
    case 'phase_advanced':
      return { kind: 'known', frame: { type: 'phase_advanced', phase: str(rec.phase, 'PENDING') } };
    case 'turn_complete':
      return { kind: 'known', frame: { type: 'turn_complete', roundId: optNum(rec.roundId) } };
    case 'round_started':
      return { kind: 'known', frame: { type: 'round_started', roundId: optNum(rec.roundId) } };
    case 'round_completed':
      return { kind: 'known', frame: { type: 'round_completed', roundId: optNum(rec.roundId) } };
    case 'usage':
      return {
        kind: 'known',
        frame: {
          type: 'usage',
          inputTokens: num(rec.inputTokens),
          outputTokens: num(rec.outputTokens),
          cacheReadTokens: num(rec.cacheReadTokens),
          costUsd: num(rec.costUsd),
        },
      };
    default:
      return { kind: 'unknown', type: type || '(missing type)', raw: rec };
  }
}
