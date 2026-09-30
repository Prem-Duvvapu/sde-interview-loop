import { useCallback, useEffect, useRef, useState } from 'react';
import type { VoiceProvider } from '../voice/VoiceProvider';
import { readStored, writeStored } from '../lib/storage';

interface Props {
  /** Sending is unavailable (e.g. the socket is down). The draft always stays editable. */
  disabled: boolean;
  disabledReason: string | null;
  awaitingReply: boolean;
  /**
   * Returns whether the turn actually left the browser. The draft is cleared only on `true`:
   * a send refused because the socket dropped must never cost the candidate their answer.
   */
  onSend: (text: string) => boolean;
  voice: VoiceProvider;
  /** Session-storage key for the unsent draft, scoped to one round. */
  draftKey?: string | null;
}

const MAX_ROWS_PX = 240;

export function Composer({ disabled, disabledReason, awaitingReply, onSend, voice, draftKey = null }: Props) {
  const [text, setText] = useState(() => (draftKey ? readStored(draftKey, 'session') ?? '' : ''));
  const [listening, setListening] = useState(false);
  const [voiceNotice, setVoiceNotice] = useState<string | null>(null);
  const [sendNotice, setSendNotice] = useState<string | null>(null);
  const areaRef = useRef<HTMLTextAreaElement | null>(null);
  const dictationBaseRef = useRef('');

  const autoGrow = useCallback(() => {
    const el = areaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, MAX_ROWS_PX)}px`;
  }, []);

  useEffect(autoGrow, [text, autoGrow]);

  // Keep the unsent draft across a reconnect or reload of this round. Best-effort only.
  useEffect(() => {
    if (draftKey) writeStored(draftKey, text === '' ? null : text, 'session');
  }, [draftKey, text]);

  const canSubmit = !disabled && !awaitingReply;

  const submit = useCallback(() => {
    const value = text.trim();
    if (!value || !canSubmit) return;
    // Detach dictation *before* sending: a late recognition result must not re-fill the
    // composer with the answer that was just sent.
    voice.cancelListening();
    setListening(false);
    const sent = onSend(value);
    if (!sent) {
      setSendNotice('Not sent — the connection is down. Your answer is kept here; send it again once reconnected.');
      return;
    }
    setSendNotice(null);
    setText('');
    requestAnimationFrame(() => areaRef.current?.focus());
  }, [text, canSubmit, onSend, voice]);

  const toggleDictation = useCallback(() => {
    if (listening) {
      voice.stopListening();
      setListening(false);
      return;
    }
    setVoiceNotice(null);
    dictationBaseRef.current = text.trimEnd();
    const started = voice.startListening({
      onUpdate: ({ finalText, interimText }) => {
        const dictated = [finalText, interimText].filter(Boolean).join(' ');
        const base = dictationBaseRef.current;
        setText(dictated ? `${base}${base ? ' ' : ''}${dictated}` : base);
      },
      onEnd: () => setListening(false),
      onError: (message) => {
        setListening(false);
        setVoiceNotice(message);
      },
    });
    if (started) {
      setListening(true);
      window.setTimeout(() => areaRef.current?.focus(), 0);
    } else if (!voice.supportsRecognition()) {
      setVoiceNotice('Speech recognition is not available in this browser. You can still type your answer.');
    }
  }, [listening, text, voice]);

  // Leaving the round (or switching rounds, which remounts this component) ends dictation for good.
  useEffect(() => () => voice.cancelListening(), [voice]);

  const onChange = useCallback(
    (value: string) => {
      if (listening) {
        // Typing while dictating would be overwritten by the next recognition update, so manual
        // editing takes over: dictation stops and pending results are discarded.
        voice.cancelListening();
        setListening(false);
        setVoiceNotice('Dictation stopped because you edited the answer. Your text is kept.');
      }
      setSendNotice(null);
      setText(value);
    },
    [listening, voice],
  );

  const onKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        submit();
      }
    },
    [submit],
  );

  const isMac = typeof navigator !== 'undefined' && navigator.platform.includes('Mac');
  const hint = disabled && disabledReason
    ? <span className="hint-warn">{disabledReason}</span>
    : awaitingReply
      ? <span>The interviewer is replying. You can keep drafting; Send unlocks when they finish.</span>
      : <><kbd>{isMac ? '⌘' : 'Ctrl'}</kbd> + <kbd>Enter</kbd> sends · your current code or diagram goes with it</>;

  return (
    <form
      className="composer"
      aria-label="Your answer"
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <label className="visually-hidden" htmlFor="composer-input">
        Your answer
      </label>
      <textarea
        id="composer-input"
        ref={areaRef}
        className="composer-input"
        rows={2}
        value={text}
        placeholder="Think out loud…"
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={onKeyDown}
        aria-describedby="composer-hint"
        spellCheck
      />
      <div className="composer-foot">
        <span className="composer-hint" id="composer-hint">{hint}</span>
        <div className="composer-actions">
          <button
            type="button"
            className={`btn btn-ghost voice-mic${listening ? ' is-listening' : ''}`}
            onClick={toggleDictation}
            disabled={disabled || !voice.supportsRecognition()}
            aria-pressed={listening}
            title={voice.supportsRecognition() ? (listening ? 'Stop dictation' : 'Dictate your answer') : 'Speech recognition is not supported by this browser'}
          >
            {listening ? 'Stop mic' : 'Use mic'}
          </button>
          <button type="submit" className="btn btn-primary" disabled={!canSubmit || text.trim() === ''}>
            {awaitingReply ? 'Waiting for reply…' : 'Send'}
          </button>
        </div>
      </div>
      <div className="composer-notices" aria-live="polite">
        {listening && <p className="voice-notice is-live">Listening — dictated text stays editable and is never sent automatically.</p>}
        {voiceNotice && <p className="voice-notice">{voiceNotice}</p>}
        {sendNotice && <p className="voice-notice tone-warn">{sendNotice}</p>}
      </div>
    </form>
  );
}
