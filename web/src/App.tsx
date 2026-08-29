import { useCallback, useEffect, useRef, useState } from 'react';
import { completeRound, createSession, describeError, getSession, startRound } from './api/client';
import type { CompanyProfile, InterviewSession, ModuleTypeId, SessionModeId, SessionRound } from './api/types';
import { EMPTY_USAGE, nextId, type ChatItem, type UsageTotals } from './lib/chat';
import { defaultLanguageFor, isModuleType } from './lib/phases';
import { useInterviewSocket } from './ws/useInterviewSocket';
import type { OutboundFrame, ParsedFrame } from './ws/frames';
import { SetupView } from './components/SetupView';
import { InterviewView } from './components/InterviewView';
import { ReplayView } from './components/ReplayView';
import { DashboardView } from './components/DashboardView';
import { SettingsOverlay } from './components/SettingsOverlay';

type View =
  | { kind: 'setup' }
  | { kind: 'interview' }
  | { kind: 'replay'; roundId: number }
  | { kind: 'dashboard' };

export function App() {
  const [view, setView] = useState<View>({ kind: 'setup' });
  const [settingsOpen, setSettingsOpen] = useState(false);

  const [profile, setProfile] = useState<CompanyProfile | null>(null);
  const [session, setSession] = useState<InterviewSession | null>(null);
  const [round, setRound] = useState<SessionRound | null>(null);
  const [moduleType, setModuleType] = useState<ModuleTypeId>('dsa');

  const [items, setItems] = useState<ChatItem[]>([]);
  const [phase, setPhase] = useState('BRIEFING');
  const [usage, setUsage] = useState<UsageTotals>(EMPTY_USAGE);
  const [awaitingReply, setAwaitingReply] = useState(false);
  const [roundComplete, setRoundComplete] = useState(false);
  const [startedAtMs, setStartedAtMs] = useState<number | null>(null);

  const [language, setLanguage] = useState('java');
  const [resetToken, setResetToken] = useState(0);

  const [starting, setStarting] = useState(false);
  const [startError, setStartError] = useState<string | null>(null);

  /** The editor buffer. A ref on purpose — keystrokes must not re-render the transcript. */
  const bufferRef = useRef('');
  const roundIdRef = useRef<number | null>(null);
  const sessionIdRef = useRef<number | null>(null);
  const startSentForRoundRef = useRef<number | null>(null);
  /** Assigned once the socket hook has run; lets callbacks defined above it send frames. */
  const sendRef = useRef<((frame: OutboundFrame) => boolean) | null>(null);
  const beginNextRoundRef = useRef<((next: SessionRound, skippedRoundOrdinals?: number[]) => Promise<void>) | null>(null);
  const reportedUnknownFramesRef = useRef<Set<string>>(new Set());

  const pushItem = useCallback((item: ChatItem) => setItems((prev) => [...prev, item]), []);

  const pushSystem = useCallback(
    (text: string, tone: 'info' | 'warn' | 'error' = 'info') =>
      pushItem({ id: nextId('sys'), kind: 'system', at: Date.now(), text, tone }),
    [pushItem],
  );

  // ------------------------------------------------------------ frame handling

  const handleFrame = useCallback(
    (parsed: ParsedFrame) => {
      if (parsed.kind === 'invalid') {
        console.warn('[ws] unreadable frame payload:', parsed.raw);
        pushSystem('Received an unreadable frame from the backend — ignored.', 'warn');
        return;
      }

      if (parsed.kind === 'unknown') {
        console.warn('[ws] unhandled frame type:', parsed.type, parsed.raw);
        if (!reportedUnknownFramesRef.current.has(parsed.type)) {
          reportedUnknownFramesRef.current.add(parsed.type);
          pushSystem(`Backend sent an unrecognised frame type "${parsed.type}" — ignored.`, 'info');
        }
        return;
      }

      const frame = parsed.frame;
      switch (frame.type) {
        case 'pong':
          // Latency is tracked inside the socket hook; nothing to show in the transcript.
          break;

        case 'turn_ack':
          setAwaitingReply(true);
          break;

        case 'text_delta':
          setItems((prev) => {
            const last = prev[prev.length - 1];
            if (last && last.kind === 'interviewer' && last.streaming) {
              const updated: ChatItem = { ...last, text: last.text + frame.text };
              return [...prev.slice(0, -1), updated];
            }
            return [
              ...prev,
              { id: nextId('int'), kind: 'interviewer', at: Date.now(), text: frame.text, streaming: true },
            ];
          });
          break;

        case 'turn_complete':
          setItems((prev) => {
            const last = prev[prev.length - 1];
            if (last && last.kind === 'interviewer' && last.streaming) {
              return [...prev.slice(0, -1), { ...last, streaming: false }];
            }
            return prev;
          });
          setAwaitingReply(false);
          break;

        case 'tool_call':
          pushItem({
            id: nextId('tool'),
            kind: 'tool',
            at: Date.now(),
            name: frame.name,
            args: frame.arguments,
          });
          break;

        case 'phase_advanced':
          setPhase(frame.phase);
          pushSystem(`Phase → ${frame.phase.replace(/_/g, ' ').toLowerCase()}`, 'info');
          break;

        case 'round_started':
          setStartedAtMs((prev) => prev ?? Date.now());
          setRoundComplete(false);
          break;

        case 'round_completed':
          setRoundComplete(true);
          setAwaitingReply(false);
          pushSystem('Round complete.', 'info');
          break;

        case 'next_round_ready':
          if (!frame.round) {
            pushSystem('The next round was prepared, but its details were unreadable. Return to Sessions and resume it there.', 'warn');
            break;
          }
          void beginNextRoundRef.current?.(frame.round, frame.skippedRoundOrdinals);
          break;

        case 'usage':
          setUsage((prev) => ({
            inputTokens: prev.inputTokens + frame.inputTokens,
            outputTokens: prev.outputTokens + frame.outputTokens,
            cacheReadTokens: prev.cacheReadTokens + frame.cacheReadTokens,
            costUsd: prev.costUsd + frame.costUsd,
            calls: prev.calls + 1,
          }));
          break;

        case 'error':
          setAwaitingReply(false);
          pushSystem(frame.message, 'error');
          break;

        default: {
          // Exhaustiveness guard — a new KnownFrame member will fail the build here.
          const never: never = frame;
          console.warn('[ws] frame fell through switch:', never);
          break;
        }
      }
    },
    [pushItem, pushSystem],
  );

  const socketEnabled = view.kind === 'interview' && round !== null;

  const handleSocketOpen = useCallback(() => {
    const id = roundIdRef.current;
    if (id === null) return;
    if (startSentForRoundRef.current === id) return;
    startSentForRoundRef.current = id;
    // Announce the round on the socket the backend will stream on. The REST start call
    // has already moved the round into IN_PROGRESS; this binds it to this connection.
    sendRef.current?.({ type: 'start_round', roundId: id });
  }, []);

  const handleSocketClose = useCallback(
    (wasClean: boolean) => {
      // Allow start_round to be re-sent on the next successful connection.
      startSentForRoundRef.current = null;
      if (!wasClean && roundIdRef.current !== null) {
        setAwaitingReply(false);
      }
    },
    [],
  );

  const socket = useInterviewSocket({
    enabled: socketEnabled,
    onFrame: handleFrame,
    onOpen: handleSocketOpen,
    onClose: handleSocketClose,
  });
  sendRef.current = socket.send;

  // ------------------------------------------------------------ session lifecycle

  const resetRoundState = useCallback((module: ModuleTypeId, initialPhase: string) => {
    setItems([]);
    setUsage(EMPTY_USAGE);
    setPhase(initialPhase);
    setAwaitingReply(false);
    setRoundComplete(false);
    setStartedAtMs(Date.now());
    setLanguage(defaultLanguageFor(module));
    setResetToken((t) => t + 1);
    bufferRef.current = '';
    reportedUnknownFramesRef.current = new Set();
    startSentForRoundRef.current = null;
  }, []);

  const beginNextRound = useCallback(
    async (next: SessionRound, skippedRoundOrdinals: number[] = []) => {
      const sessionId = sessionIdRef.current;
      if (sessionId === null) return;

      let opened = next;
      try {
        opened = (await startRound(sessionId, next.id)) ?? next;
      } catch (err: unknown) {
        pushSystem(`Could not start the next round: ${describeError(err)}`, 'warn');
        return;
      }

      if (!isModuleType(opened.moduleType)) {
        pushSystem('The next round uses a module this client does not recognise.', 'warn');
        return;
      }

      setRound(opened);
      setModuleType(opened.moduleType);
      roundIdRef.current = opened.id;
      resetRoundState(opened.moduleType, opened.phase && opened.phase !== 'PENDING' ? opened.phase : 'BRIEFING');
      setSession((previous) => previous
        ? {
            ...previous,
            rounds: (previous.rounds ?? []).map((candidate) => (candidate.id === opened.id ? opened : candidate)),
          }
        : previous);

      const sent = sendRef.current?.({ type: 'start_round', roundId: opened.id }) ?? false;
      if (sent) startSentForRoundRef.current = opened.id;

      window.setTimeout(() => {
        if (skippedRoundOrdinals.length > 0) {
          pushSystem(`Skipped profile round${skippedRoundOrdinals.length === 1 ? '' : 's'} ${skippedRoundOrdinals.join(', ')} because ${skippedRoundOrdinals.length === 1 ? 'it is' : 'they are'} disabled in this version.`, 'info');
        }
        pushSystem(`Starting round ${opened.ordinal}.`, 'info');
        if (!sent) pushSystem('Connecting to the next interviewer…', 'info');
      }, 0);
    },
    [pushSystem, resetRoundState],
  );
  beginNextRoundRef.current = beginNextRound;

  const handleStart = useCallback(
    async (opts: {
      profile: CompanyProfile;
      mode: SessionModeId;
      moduleType: ModuleTypeId;
      difficultyTarget: string;
    }) => {
      setStarting(true);
      setStartError(null);
      try {
        const created = await createSession({
          companyProfileId: opts.profile.id,
          mode: opts.mode,
          moduleType: opts.moduleType,
          difficultyTarget: opts.difficultyTarget,
        });

        const rounds = created?.rounds ?? [];
        const target =
          (opts.mode === 'full_loop' ? rounds.find((r) => r.status === 'PENDING') : undefined)
          ?? rounds.find((r) => r.moduleType === opts.moduleType && r.status !== 'COMPLETED')
          ?? rounds[0];
        if (!target) {
          throw new Error('The backend created a session with no rounds.');
        }

        let opened = target;
        let startWarning: string | null = null;
        try {
          opened = (await startRound(created.id, target.id)) ?? target;
        } catch (err: unknown) {
          // The socket can still drive the round; surface it but do not block.
          startWarning = describeError(err);
        }

        const resolvedModule: ModuleTypeId =
          typeof opened.moduleType === 'string' && isModuleType(opened.moduleType)
            ? opened.moduleType
            : opts.moduleType;

        setProfile(opts.profile);
        setSession(created);
        sessionIdRef.current = created.id;
        setRound(opened);
        setModuleType(resolvedModule);
        roundIdRef.current = opened.id;
        resetRoundState(resolvedModule, opened.phase && opened.phase !== 'PENDING' ? opened.phase : 'BRIEFING');
        setView({ kind: 'interview' });

        if (startWarning) {
          // Queue after the reset so it is not wiped.
          window.setTimeout(
            () => pushSystem(`Round start over REST failed: ${startWarning}`, 'warn'),
            0,
          );
        }
      } catch (err: unknown) {
        setStartError(describeError(err));
      } finally {
        setStarting(false);
      }
    },
    [pushSystem, resetRoundState],
  );

  const handleSend = useCallback(
    (text: string) => {
      const id = roundIdRef.current;
      if (id === null) return;
      const buffer = bufferRef.current;
      const artifact = buffer.trim() === '' ? null : buffer;

      const sent = socket.send({ type: 'candidate_turn', roundId: id, text, artifact });
      if (!sent) {
        pushSystem('Turn not sent — the socket is not open. It will retry connecting.', 'warn');
        return;
      }

      pushItem({
        id: nextId('cand'),
        kind: 'candidate',
        at: Date.now(),
        text,
        artifactChars: artifact ? artifact.length : 0,
      });
      setAwaitingReply(true);
    },
    [pushItem, pushSystem, socket],
  );

  const handleEndRound = useCallback(async () => {
    const current = round;
    if (!current || !session) return;
    setRoundComplete(true);
    try {
      const updated = await completeRound(session.id, current.id);
      if (updated) setRound(updated);
      pushSystem('Round marked complete.', 'info');
      if (session.mode === 'FULL_LOOP') {
        const refreshed = await getSession(session.id);
        setSession(refreshed);
        const next = (refreshed.rounds ?? []).find((candidate) => candidate.status === 'PENDING');
        if (next) {
          const skipped = (refreshed.rounds ?? [])
            .filter((candidate) => candidate.ordinal > current.ordinal && candidate.ordinal < next.ordinal && candidate.status === 'SKIPPED')
            .map((candidate) => candidate.ordinal);
          await beginNextRound(next, skipped);
        }
      }
    } catch (err: unknown) {
      pushSystem(`Could not mark the round complete: ${describeError(err)}`, 'warn');
    }
  }, [round, session, pushSystem, beginNextRound]);

  const handleExit = useCallback(() => {
    roundIdRef.current = null;
    sessionIdRef.current = null;
    startSentForRoundRef.current = null;
    setRound(null);
    setSession(null);
    setView({ kind: 'setup' });
  }, []);

  useEffect(() => {
    document.title = view.kind === 'interview' && profile ? `${profile.displayName ?? profile.id} · Interview Loop` : 'SDE Interview Loop';
  }, [view.kind, profile]);

  // ------------------------------------------------------------ render

  return (
    <>
      {view.kind === 'setup' && (
        <SetupView
          onStart={(opts) => void handleStart(opts)}
          onReplay={(roundId) => setView({ kind: 'replay', roundId })}
          onOpenDashboard={() => setView({ kind: 'dashboard' })}
          onOpenSettings={() => setSettingsOpen(true)}
          starting={starting}
          startError={startError}
        />
      )}

      {view.kind === 'interview' && round && (
        <InterviewView
          profile={profile}
          round={round}
          roundCount={session?.rounds?.length ?? 1}
          moduleType={moduleType}
          phase={phase}
          roundComplete={roundComplete}
          items={items}
          usage={usage}
          socket={socket}
          awaitingReply={awaitingReply}
          startedAtMs={startedAtMs}
          language={language}
          resetToken={resetToken}
          onLanguageChange={setLanguage}
          onBufferChange={(v) => {
            bufferRef.current = v;
          }}
          onSend={handleSend}
          onEndRound={() => void handleEndRound()}
          onExit={handleExit}
          onOpenSettings={() => setSettingsOpen(true)}
          onReconnect={socket.reconnectNow}
        />
      )}

      {view.kind === 'replay' && (
        <ReplayView roundId={view.roundId} onExit={() => setView({ kind: 'setup' })} />
      )}

      {view.kind === 'dashboard' && (
        <DashboardView
          onExit={() => setView({ kind: 'setup' })}
          onReplay={(roundId) => setView({ kind: 'replay', roundId })}
          onOpenSettings={() => setSettingsOpen(true)}
        />
      )}

      <SettingsOverlay open={settingsOpen} onClose={() => setSettingsOpen(false)} />
    </>
  );
}
