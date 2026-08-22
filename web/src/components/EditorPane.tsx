import { useCallback, useEffect, useRef, useState } from 'react';
import Editor, { type OnMount } from '@monaco-editor/react';
import { EDITOR_LANGUAGES, MONACO_THEME, STARTER_BUFFERS } from '../monaco-setup';

type MonacoEditor = Parameters<OnMount>[0];

interface Props {
  language: string;
  onLanguageChange: (language: string) => void;
  /** Called on every keystroke. Must be cheap — the parent writes it to a ref, not to state. */
  onBufferChange: (value: string) => void;
  title: string;
  readOnly?: boolean;
  /** Bumped by the parent to force the starter buffer back in (new round, reset). */
  resetToken: number;
}

const DEBOUNCE_MS = 400;

/**
 * Monaco lives here and nowhere else.
 *
 * The buffer is intentionally *not* React state: `onBufferChange` writes straight to a
 * ref in the parent, and only a debounced character count re-renders. Keystrokes
 * therefore never trigger a React render of the transcript pane, which is what keeps
 * typing smooth while a response is streaming in beside it.
 */
export function EditorPane({
  language,
  onLanguageChange,
  onBufferChange,
  title,
  readOnly = false,
  resetToken,
}: Props) {
  const editorRef = useRef<MonacoEditor | null>(null);
  const prevLanguageRef = useRef(language);
  const [chars, setChars] = useState(0);
  const [lines, setLines] = useState(0);
  const debounceRef = useRef<number | null>(null);
  const [ready, setReady] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  // Monaco is bundled, so a slow mount means something is genuinely wrong rather than
  // a slow network. Surface a designed state instead of an indefinite "Loading…".
  useEffect(() => {
    if (ready) return;
    const id = window.setTimeout(() => setLoadFailed(true), 12_000);
    return () => window.clearTimeout(id);
  }, [ready]);

  const starter = STARTER_BUFFERS[language] ?? '';

  const handleMount: OnMount = useCallback(
    (editor) => {
      editorRef.current = editor;
      setReady(true);
      const value = editor.getValue();
      onBufferChange(value);
      setChars(value.length);
      setLines(editor.getModel()?.getLineCount() ?? 0);
    },
    [onBufferChange],
  );

  const handleChange = useCallback(
    (value: string | undefined) => {
      const text = value ?? '';
      onBufferChange(text);
      if (debounceRef.current !== null) window.clearTimeout(debounceRef.current);
      debounceRef.current = window.setTimeout(() => {
        setChars(text.length);
        setLines(editorRef.current?.getModel()?.getLineCount() ?? 0);
      }, DEBOUNCE_MS);
    },
    [onBufferChange],
  );

  useEffect(
    () => () => {
      if (debounceRef.current !== null) window.clearTimeout(debounceRef.current);
    },
    [],
  );

  // Swap in the new language's starter only when the buffer is still pristine — never
  // clobber work the candidate has already typed.
  useEffect(() => {
    const editor = editorRef.current;
    const previous = prevLanguageRef.current;
    prevLanguageRef.current = language;
    if (!editor || previous === language) return;

    const current = editor.getValue();
    const pristine = current.trim() === '' || current === (STARTER_BUFFERS[previous] ?? '');
    if (pristine) {
      const next = STARTER_BUFFERS[language] ?? '';
      editor.setValue(next);
      onBufferChange(next);
      setChars(next.length);
    }
  }, [language, onBufferChange]);

  const resetBuffer = useCallback(() => {
    const editor = editorRef.current;
    const next = STARTER_BUFFERS[language] ?? '';
    if (editor) {
      editor.setValue(next);
      editor.focus();
    }
    onBufferChange(next);
    setChars(next.length);
  }, [language, onBufferChange]);

  useEffect(() => {
    if (resetToken === 0) return;
    resetBuffer();
    // resetBuffer is stable per language; the token is the trigger.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [resetToken]);

  return (
    <section className="pane editor-pane" aria-label={title}>
      <div className="pane-head">
        <h2>{title}</h2>
        <div className="editor-tools">
          <label className="visually-hidden" htmlFor="editor-language">
            Editor language
          </label>
          <select
            id="editor-language"
            className="select-sm"
            value={language}
            onChange={(e) => onLanguageChange(e.target.value)}
            disabled={readOnly}
          >
            {EDITOR_LANGUAGES.map((l) => (
              <option key={l.id} value={l.id}>
                {l.label}
              </option>
            ))}
          </select>
          <button type="button" className="link-btn" onClick={resetBuffer} disabled={readOnly}>
            Reset
          </button>
          <span className="pane-sub" title="Sent as the `artifact` field on your next turn">
            {lines} ln · {chars.toLocaleString()} ch
          </span>
        </div>
      </div>

      <div className="editor-host">
        {loadFailed ? (
          <div className="empty-state editor-fallback">
            <p className="empty-title">Editor failed to load.</p>
            <p className="empty-body">
              Monaco is bundled with the app, so this normally means the page needs a reload.
            </p>
          </div>
        ) : (
          <Editor
            defaultValue={starter}
            language={language}
            theme={MONACO_THEME}
            onMount={handleMount}
            onChange={handleChange}
            loading={<div className="editor-loading">Loading editor…</div>}
            options={{
              readOnly,
              fontSize: 13.5,
              lineHeight: 21,
              fontFamily: '"JetBrains Mono", "Cascadia Code", ui-monospace, SFMono-Regular, Consolas, monospace',
              minimap: { enabled: false },
              scrollBeyondLastLine: false,
              renderLineHighlight: 'line',
              smoothScrolling: true,
              cursorBlinking: 'smooth',
              automaticLayout: true,
              tabSize: 4,
              padding: { top: 14, bottom: 14 },
              bracketPairColorization: { enabled: true },
              guides: { indentation: true },
              scrollbar: { verticalScrollbarSize: 10, horizontalScrollbarSize: 10 },
              overviewRulerLanes: 0,
              wordWrap: 'off',
            }}
          />
        )}
      </div>
    </section>
  );
}
