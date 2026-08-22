import { useCallback, useEffect, useRef, useState } from 'react';

interface Props {
  disabled: boolean;
  disabledReason: string | null;
  awaitingReply: boolean;
  onSend: (text: string) => void;
}

const MAX_ROWS_PX = 200;

export function Composer({ disabled, disabledReason, awaitingReply, onSend }: Props) {
  const [text, setText] = useState('');
  const areaRef = useRef<HTMLTextAreaElement | null>(null);

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
    setText('');
    requestAnimationFrame(() => areaRef.current?.focus());
  }, [text, disabled, onSend]);

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
        <button type="submit" className="btn btn-primary" disabled={disabled || text.trim() === ''}>
          {awaitingReply ? 'Send anyway' : 'Send turn'}
        </button>
      </div>
    </form>
  );
}
