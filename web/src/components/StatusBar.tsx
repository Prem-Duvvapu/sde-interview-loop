import type { SocketState } from '../ws/useInterviewSocket';
import type { UsageTotals } from '../lib/chat';
import { formatCost, formatTokens } from '../lib/format';

interface Props {
  socket: SocketState;
  usage: UsageTotals;
  provider: string | null;
  model: string | null;
  onReconnect: () => void;
}

const STATUS_TEXT: Record<SocketState['status'], string> = {
  idle: 'Idle',
  connecting: 'Connecting',
  open: 'Live',
  reconnecting: 'Reconnecting',
  closed: 'Disconnected',
};

/**
 * Deliberately the quietest thing on screen. Connection, tokens and cost matter, but
 * they must not compete with the transcript during a round.
 */
export function StatusBar({ socket, usage, provider, model, onReconnect }: Props) {
  const retrySeconds = socket.retryInMs === null ? null : Math.ceil(socket.retryInMs / 1000);

  return (
    <footer className="status-bar">
      <span className={`conn conn-${socket.status}`}>
        <span className="conn-dot" aria-hidden="true" />
        {STATUS_TEXT[socket.status]}
        {socket.status === 'reconnecting' && retrySeconds !== null && (
          <span className="conn-detail">retry in {retrySeconds}s · attempt {socket.attempts}</span>
        )}
        {socket.status === 'open' && socket.latencyMs !== null && (
          <span className="conn-detail">{socket.latencyMs}ms</span>
        )}
      </span>

      {(socket.status === 'reconnecting' || socket.status === 'closed') && (
        <button type="button" className="link-btn" onClick={onReconnect}>
          Retry now
        </button>
      )}

      <span className="status-spacer" />

      {provider && (
        <span className="status-item" title="Interviewer provider and model for this round">
          {provider}
          {model ? ` · ${model}` : ''}
        </span>
      )}
      <span className="status-item" title="Tokens in / out / read from cache">
        {formatTokens(usage.inputTokens)} in · {formatTokens(usage.outputTokens)} out
        {usage.cacheReadTokens > 0 && <> · {formatTokens(usage.cacheReadTokens)} cached</>}
      </span>
      <span className="status-item status-cost" title={`${usage.calls} LLM call(s) this round`}>
        {formatCost(usage.costUsd)}
      </span>
    </footer>
  );
}
