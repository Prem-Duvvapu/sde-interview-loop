import { useCallback, useEffect, useRef, useState } from 'react';
import type { VoiceProvider } from '../voice/VoiceProvider';

interface Props {
  disabled: boolean;
  disabledReason: string | null;
  awaitingReply: boolean;
  onSend: (text: string) => void;
  voice: VoiceProvider;
}

const MAX_ROWS_PX = 200;

export function Composer({ disabled, disabledReason, awaitingReply, onSend, voice }: Props) {
  const [text, setText] = useState('');
  const [listening, setListening] = useState(false);
  const [voiceNotice, setVoiceNotice] = useState<string | null>(null);
  const areaRef = useRef<HTMLTextAreaElement | null>(null);
  const dictationPrefixRef = useRef('');

  const autoGrow = useCallback(() => {
    const el = areaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, MAX_ROWS_PX)}px`;
  }, []);

  useEffect(autoGrow, [text, autoGrow]);

  const submit = useCallback(() => {
    const value = text.trim();
    if (!value || disabled) return;
    onSend(value);
    voice.stopListening();
    setListening(false);
    setText('');
    requestAnimationFrame(() => areaRef.current?.focus());
  }, [text, disabled, onSend, voice]);

  const toggleDictation = useCallback(() => {
    if (listening) {
      voice.stopListening();
      return;
    }
    setVoiceNotice(null);
    dictationPrefixRef.current = text.trimEnd();
    const started = voice.startListening({
      onUpdate: ({ finalText, interimText }) => {
        const dictated = [finalText, interimText].filter(Boolean).join(' ');
        const prefix = dictationPrefixRef.current;
        setText(dictated ? `${prefix}${prefix ? ' ' : ''}${dictated}` : prefix);
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
    } else {
      setVoiceNotice('Speech recognition is not available in this browser. You can still type your answer.');
    }
  }, [listening, text, voice]);

  useEffect(() => () => voice.stopListening(), [voice]);

  const onKeyDown = useCallback(
    (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
      if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) {
        e.preventDefault();
        submit();
      }
    },
    [submit],
  );

  return (
    <form
      className="composer"
      onSubmit={(e) => {
        e.preventDefault();
        submit();
      }}
    >
      <label className="visually-hidden" htmlFor="composer-input">
        Your turn
      </label>
      <textarea
        id="composer-input"
        ref={areaRef}
        className="composer-input"
        rows={2}
        value={text}
        placeholder={disabled ? (disabledReason ?? 'Not connected') : 'Think out loud…'}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={onKeyDown}
        disabled={disabled}
        spellCheck={false}
      />
      <div className="composer-foot">
        <span className="composer-hint">
          {disabled && disabledReason ? (
            <span className="hint-warn">{disabledReason}</span>
          ) : (
            <>
              <kbd>{navigator.platform.includes('Mac') ? '⌘' : 'Ctrl'}</kbd> + <kbd>Enter</kbd> to send · the
              editor buffer goes with it
            </>
          )}
        </span>
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
          <button type="submit" className="btn btn-primary" disabled={disabled || text.trim() === ''}>
            {awaitingReply ? 'Send anyway' : 'Send turn'}
          </button>
        </div>
      </div>
      {voiceNotice && <p className="voice-notice" role="status">{voiceNotice}</p>}
    </form>
  );
}
