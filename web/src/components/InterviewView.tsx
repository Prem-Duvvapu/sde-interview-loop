import { useMemo, useState } from 'react';
import type { CompanyProfile, ModuleTypeId, SessionRound } from '../api/types';
import type { ChatItem, UsageTotals } from '../lib/chat';
import type { SocketState } from '../ws/useInterviewSocket';
import { MODULE_LABELS, artifactLabelFor } from '../lib/phases';
import { formatClock } from '../lib/format';
import { useTicker } from '../lib/hooks';
import { PhaseStrip } from './PhaseStrip';
import { TranscriptPane } from './TranscriptPane';
import { EditorPane } from './EditorPane';
import { DiagramPane } from './DiagramPane';
import { Composer } from './Composer';
import { StatusBar } from './StatusBar';
import type { SpeechState, VoiceProvider } from '../voice/VoiceProvider';
import type { Completion } from '../lib/completion';
import { CompletionPanel } from './CompletionPanel';
import { GENERAL_PRACTICE_LABEL } from '../lib/generalPractice';

interface Props {
  profile: CompanyProfile | null;
  round: SessionRound;
  completion: Completion;
  onReviewRound: (roundId: number) => void;
  speechState: SpeechState;
  onStopSpeech: () => void;
  roundCount: number;
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
  onSend: (text: string) => boolean;
  onEndRound: () => void;
  onExit: () => void;
  onOpenSettings: () => void;
  onReconnect: () => void;
  voice: VoiceProvider;
  ttsEnabled: boolean;
  onToggleTts: () => void;
}

export function InterviewView(props: Props) {
  const {
    profile,
    round,
    completion,
    onReviewRound,
    speechState,
    onStopSpeech,
    roundCount,
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
    voice,
    ttsEnabled,
    onToggleTts,
  } = props;

  const [confirmingEnd, setConfirmingEnd] = useState(false);
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
  const disabledReason = socket.status === 'open'
    ? null
    : socket.status === 'reconnecting' || socket.status === 'connecting'
      ? 'Connecting… your draft is kept here; Send unlocks once the connection is back.'
      : 'Not connected to the backend. Your draft is kept here.';
  const isFullLoop = roundCount > 1;

  return (
    <div className="app-shell">
      <header className="topbar">
        <button type="button" className="btn btn-ghost btn-sm" onClick={onExit} title="Leave this round">
          ← Sessions
        </button>

        <div className="topbar-title">
          <span className="topbar-primary">{profile?.displayName ?? GENERAL_PRACTICE_LABEL}</span>
          <span className="topbar-secondary">
            {MODULE_LABELS[moduleType]}
            {round.difficultyTarget ? ` · ${round.difficultyTarget}` : ''}
            {` · round ${round.ordinal}${roundCount > 1 ? ` of ${roundCount}` : ''}`}
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
            className={`btn btn-ghost btn-sm voice-toggle${ttsEnabled ? ' is-active' : ''}`}
            onClick={onToggleTts}
            disabled={!voice.supportsSynthesis()}
            aria-pressed={ttsEnabled}
            title={voice.supportsSynthesis() ? 'Read completed interviewer replies aloud' : 'Text-to-speech is not supported by this browser'}
          >
            {ttsEnabled ? 'Voice on' : 'Voice off'}
          </button>
          {speechState === 'speaking' && (
            <button type="button" className="btn btn-ghost btn-sm" onClick={onStopSpeech}>
              Stop reading
            </button>
          )}
          <button
            type="button"
            className="btn btn-danger-ghost btn-sm"
            onClick={() => setConfirmingEnd(true)}
            disabled={roundComplete || confirmingEnd}
          >
            End round
          </button>
        </div>
      </header>

      {confirmingEnd && !roundComplete && (
        <div className="confirm-bar" role="alertdialog" aria-labelledby="confirm-end-title" aria-describedby="confirm-end-body">
          <p>
            <strong id="confirm-end-title">End this round now?</strong>{' '}
            <span id="confirm-end-body">It will be scored on what you have said and written so far. This cannot be undone.</span>
          </p>
          <div className="confirm-actions">
            <button
              type="button"
              className="btn btn-danger btn-sm"
              onClick={() => {
                setConfirmingEnd(false);
                onEndRound();
              }}
            >
              End and score
            </button>
            <button type="button" className="btn btn-ghost btn-sm" autoFocus onClick={() => setConfirmingEnd(false)}>
              Keep going
            </button>
          </div>
        </div>
      )}

      <PhaseStrip moduleType={moduleType} currentPhase={phase} roundComplete={roundComplete} />

      <div className="pane-row">
        <div className="left-column">
          <TranscriptPane
            items={items}
            awaitingReply={awaitingReply}
            roundLabel={`${MODULE_LABELS[moduleType]} · ${items.length} entries`}
          />
          {roundComplete || completion.state !== 'active' ? (
            <CompletionPanel
              completion={completion}
              isFullLoop={isFullLoop}
              onReview={onReviewRound}
              onExit={onExit}
            />
          ) : (
            <Composer
              key={round.id}
              disabled={!canSend}
              disabledReason={disabledReason}
              awaitingReply={awaitingReply}
              onSend={onSend}
              voice={voice}
              draftKey={`draft.round.${round.id}`}
            />
          )}
        </div>

        {/* DM-2: HLD works on a structured component graph, not a code buffer. */}
        {moduleType === 'hld' ? (
          <DiagramPane
            title={artifactLabelFor(moduleType)}
            onBufferChange={onBufferChange}
            readOnly={roundComplete}
            resetToken={resetToken}
          />
        ) : (
          <EditorPane
            language={language}
            onLanguageChange={onLanguageChange}
            onBufferChange={onBufferChange}
            title={artifactLabelFor(moduleType)}
            readOnly={roundComplete}
            resetToken={resetToken}
          />
        )}
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
