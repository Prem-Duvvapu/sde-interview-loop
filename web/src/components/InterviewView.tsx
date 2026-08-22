import { useMemo } from 'react';
import type { CompanyProfile, ModuleTypeId, SessionRound } from '../api/types';
import type { ChatItem, UsageTotals } from '../lib/chat';
import type { SocketState } from '../ws/useInterviewSocket';
import { MODULE_LABELS, artifactLabelFor } from '../lib/phases';
import { formatClock } from '../lib/format';
import { useTicker } from '../lib/hooks';
import { PhaseStrip } from './PhaseStrip';
import { TranscriptPane } from './TranscriptPane';
import { EditorPane } from './EditorPane';
import { Composer } from './Composer';
import { StatusBar } from './StatusBar';

interface Props {
  profile: CompanyProfile | null;
  round: SessionRound;
  moduleType: ModuleTypeId;
  phase: string;
  roundComplete: boolean;
  items: ChatItem[];
  usage: UsageTotals;
  socket: SocketState;
  awaitingReply: boolean;
  startedAtMs: number | null;
  language: string;
  resetToken: number;
  onLanguageChange: (l: string) => void;
  onBufferChange: (v: string) => void;
  onSend: (text: string) => void;
  onEndRound: () => void;
  onExit: () => void;
  onOpenSettings: () => void;
  onReconnect: () => void;
}

export function InterviewView(props: Props) {
  const {
    profile,
    round,
    moduleType,
    phase,
    roundComplete,
    items,
    usage,
    socket,
    awaitingReply,
    startedAtMs,
    language,
    resetToken,
    onLanguageChange,
    onBufferChange,
    onSend,
    onEndRound,
    onExit,
    onOpenSettings,
    onReconnect,
  } = props;

  const now = useTicker(1000, startedAtMs !== null && !roundComplete);
  const planned = round.plannedDurationSec ?? null;

  const timer = useMemo(() => {
    if (startedAtMs === null) return null;
    const elapsed = Math.floor((now - startedAtMs) / 1000);
    if (planned === null) return { text: formatClock(elapsed), tone: 'calm' as const, label: 'elapsed' };
    const remaining = planned - elapsed;
    const tone = remaining <= 0 ? 'over' : remaining <= 300 ? 'urgent' : 'calm';
    return {
      text: remaining < 0 ? `+${formatClock(-remaining)}` : formatClock(remaining),
      tone,
      label: remaining < 0 ? 'over time' : 'remaining',
    };
  }, [now, startedAtMs, planned]);

  const canSend = socket.status === 'open' && !roundComplete;
  const disabledReason = roundComplete
    ? 'This round is finished.'
    : socket.status === 'open'
      ? null
      : socket.status === 'reconnecting'
        ? 'Reconnecting — your turn will send once the socket is back.'
        : 'Not connected to the backend.';

  return (
    <div className="app-shell">
      <header className="topbar">
        <button type="button" className="btn btn-ghost btn-sm" onClick={onExit} title="Leave this round">
          ← Sessions
        </button>

        <div className="topbar-title">
          <span className="topbar-primary">{profile?.displayName ?? round.questionSlug ?? 'Interview'}</span>
          <span className="topbar-secondary">
            {MODULE_LABELS[moduleType]}
            {round.difficultyTarget ? ` · ${round.difficultyTarget}` : ''}
            {` · round #${round.id}`}
          </span>
        </div>

        <div className="topbar-right">
          {timer && (
            <span className={`round-timer tone-${timer.tone}`} title={`${timer.label}${planned ? ` of ${Math.round(planned / 60)} min` : ''}`}>
              {timer.text}
            </span>
          )}
          <button type="button" className="btn btn-ghost btn-sm" onClick={onOpenSettings}>
            Settings
          </button>
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            onClick={onEndRound}
            disabled={roundComplete}
          >
            End round
          </button>
        </div>
      </header>

      <PhaseStrip moduleType={moduleType} currentPhase={phase} roundComplete={roundComplete} />

      <div className="pane-row">
        <div className="left-column">
          <TranscriptPane
            items={items}
            awaitingReply={awaitingReply}
            roundLabel={`${MODULE_LABELS[moduleType]} · ${items.length} entries`}
          />
          <Composer
            disabled={!canSend}
            disabledReason={disabledReason}
            awaitingReply={awaitingReply}
            onSend={onSend}
          />
        </div>

        <EditorPane
          language={language}
          onLanguageChange={onLanguageChange}
          onBufferChange={onBufferChange}
          title={artifactLabelFor(moduleType)}
          readOnly={roundComplete}
          resetToken={resetToken}
        />
      </div>

      <StatusBar
        socket={socket}
        usage={usage}
        provider={round.interviewerProvider ?? null}
        model={round.interviewerModel ?? null}
        onReconnect={onReconnect}
      />
    </div>
  );
}
