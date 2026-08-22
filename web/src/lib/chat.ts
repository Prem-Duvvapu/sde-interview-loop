export type ChatItem =
  | { id: string; kind: 'interviewer'; at: number; text: string; streaming: boolean }
  | { id: string; kind: 'candidate'; at: number; text: string; artifactChars: number }
  | { id: string; kind: 'system'; at: number; text: string; tone: 'info' | 'warn' | 'error' }
  | { id: string; kind: 'tool'; at: number; name: string; args: Record<string, unknown> };

let seq = 0;
export const nextId = (prefix: string): string => `${prefix}-${Date.now().toString(36)}-${(seq += 1)}`;

export interface UsageTotals {
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  costUsd: number;
  calls: number;
}

export const EMPTY_USAGE: UsageTotals = {
  inputTokens: 0,
  outputTokens: 0,
  cacheReadTokens: 0,
  costUsd: 0,
  calls: 0,
};
