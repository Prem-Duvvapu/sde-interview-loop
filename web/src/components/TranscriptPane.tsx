import { Fragment } from 'react';
import type { ChatItem } from '../lib/chat';
import { formatTimeOfDay } from '../lib/format';
import { useStickyScroll } from '../lib/hooks';

interface Props {
  items: ChatItem[];
  awaitingReply: boolean;
  roundLabel: string;
}

/**
 * Splits fenced code out of interviewer prose. Handles an *unterminated* fence, which is
 * the normal case mid-stream: the block renders as it arrives instead of appearing as
 * stray backticks and then reflowing.
 */
function segments(text: string): Array<{ code: boolean; lang: string; body: string }> {
  const out: Array<{ code: boolean; lang: string; body: string }> = [];
  const parts = text.split('```');
  parts.forEach((part, i) => {
    if (i % 2 === 0) {
      if (part) out.push({ code: false, lang: '', body: part });
    } else {
      const nl = part.indexOf('\n');
      const lang = nl === -1 ? part.trim() : part.slice(0, nl).trim();
      const body = nl === -1 ? '' : part.slice(nl + 1);
      out.push({ code: true, lang, body });
    }
  });
  return out;
}

function Prose({ text, streaming }: { text: string; streaming: boolean }) {
  const parts = segments(text);
  return (
    <div className="prose">
      {parts.map((part, i) =>
        part.code ? (
          <pre className="prose-code" key={i}>
            {part.lang && <span className="prose-code-lang">{part.lang}</span>}
            <code>{part.body}</code>
          </pre>
        ) : (
          <Fragment key={i}>
            <span className="prose-text">{part.body}</span>
          </Fragment>
        ),
      )}
      {streaming && <span className="caret" aria-hidden="true" />}
    </div>
  );
}

export function TranscriptPane({ items, awaitingReply, roundLabel }: Props) {
  const scrollRef = useStickyScroll<HTMLDivElement>(items);
  const lastIsStreaming = items.length > 0 && items[items.length - 1].kind === 'interviewer'
    ? (items[items.length - 1] as Extract<ChatItem, { kind: 'interviewer' }>).streaming
    : false;

  return (
    <section className="pane transcript-pane" aria-label="Interview transcript">
      <div className="pane-head">
        <h2>Transcript</h2>
        <span className="pane-sub">{roundLabel}</span>
      </div>

      <div className="transcript-scroll" ref={scrollRef}>
        {items.length === 0 && (
          <div className="empty-state">
            <p className="empty-title">The round is open.</p>
            <p className="empty-body">
              The interviewer speaks first once modules are wired. Until then, send a turn to exercise
              the transport — your message and the editor buffer both travel on the same socket.
            </p>
          </div>
        )}

        {items.map((item) => {
          switch (item.kind) {
            case 'interviewer':
              return (
                <article className="turn turn-interviewer" key={item.id}>
                  <header className="turn-head">
                    <span className="turn-who">Interviewer</span>
                    <time className="turn-time">{formatTimeOfDay(item.at)}</time>
                  </header>
                  <Prose text={item.text} streaming={item.streaming} />
                </article>
              );
            case 'candidate':
              return (
                <article className="turn turn-candidate" key={item.id}>
                  <header className="turn-head">
                    <span className="turn-who">You</span>
                    {item.artifactChars > 0 && (
                      <span className="turn-badge" title="Editor buffer attached to this turn">
                        +{item.artifactChars.toLocaleString()} chars
                      </span>
                    )}
                    <time className="turn-time">{formatTimeOfDay(item.at)}</time>
                  </header>
                  <div className="prose">
                    <span className="prose-text">{item.text}</span>
                  </div>
                </article>
              );
            case 'tool':
              return (
                <div className="turn turn-tool" key={item.id}>
                  <span className="tool-name">{item.name}</span>
                  <span className="tool-args">{summariseArgs(item.args)}</span>
                </div>
              );
            case 'system':
            default:
              return (
                <div className={`turn turn-system tone-${item.tone}`} key={item.id}>
                  <span className="system-text">{item.text}</span>
                  <time className="turn-time">{formatTimeOfDay(item.at)}</time>
                </div>
              );
          }
        })}

        {awaitingReply && !lastIsStreaming && (
          <div className="thinking" role="status" aria-live="polite">
            <span className="thinking-dot" />
            <span className="thinking-dot" />
            <span className="thinking-dot" />
            <span className="visually-hidden">Interviewer is responding</span>
          </div>
        )}
      </div>
    </section>
  );
}

function summariseArgs(args: Record<string, unknown>): string {
  const entries = Object.entries(args);
  if (entries.length === 0) return '';
  return entries
    .map(([k, v]) => `${k}=${typeof v === 'string' ? v : JSON.stringify(v)}`)
    .join('  ')
    .slice(0, 180);
}
