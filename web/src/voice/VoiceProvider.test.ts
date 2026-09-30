import { describe, expect, it, vi } from 'vitest';
import { BrowserVoiceProvider } from './VoiceProvider';
import { fakeVoiceWindow } from '../test/fakeVoice';

const listener = () => ({ onUpdate: vi.fn(), onEnd: vi.fn(), onError: vi.fn() });

describe('BrowserVoiceProvider recognition ownership', () => {
  it('regression: cancel detaches the session so late results never arrive', () => {
    const { win, latest } = fakeVoiceWindow();
    const voice = new BrowserVoiceProvider(win);
    const l = listener();
    expect(voice.startListening(l)).toBe(true);
    const rec = latest();
    const onresult = rec.onresult;

    voice.cancelListening();
    // A browser may still invoke the handler it captured before cancel.
    onresult?.({ results: [{ isFinal: true, 0: { transcript: 'late words' } }] } as never);
    rec.end();

    expect(l.onUpdate).not.toHaveBeenCalled();
    expect(l.onEnd).not.toHaveBeenCalled();
    expect(rec.aborted).toBe(true);
    expect(voice.isListening()).toBe(false);
  });

  it('graceful stop still delivers the final result for the current utterance', () => {
    const { win, latest } = fakeVoiceWindow();
    const voice = new BrowserVoiceProvider(win);
    const l = listener();
    voice.startListening(l);
    voice.stopListening();
    latest().emit(['final words']);
    latest().end();
    expect(l.onUpdate).toHaveBeenCalledWith({ finalText: 'final words', interimText: '' });
    expect(l.onEnd).toHaveBeenCalledTimes(1);
  });

  it('starting a new session detaches the previous one', () => {
    const { win, latest } = fakeVoiceWindow();
    const voice = new BrowserVoiceProvider(win);
    const first = listener();
    voice.startListening(first);
    const firstRec = latest();
    voice.startListening(listener());
    firstRec.emit(['stale']);
    expect(first.onUpdate).not.toHaveBeenCalled();
    expect(firstRec.aborted).toBe(true);
  });

  it('reports permission denial with a retryable message; our own abort is silent', () => {
    const { win, latest } = fakeVoiceWindow();
    const voice = new BrowserVoiceProvider(win);
    const l = listener();
    voice.startListening(l);
    latest().fail('aborted');
    expect(l.onError).not.toHaveBeenCalled();
    latest().fail('not-allowed');
    expect(l.onError).toHaveBeenCalledWith(expect.stringContaining('Use mic again'), 'not-allowed');
  });

  it('uses the selected locale', () => {
    const { win, latest } = fakeVoiceWindow();
    const voice = new BrowserVoiceProvider(win);
    voice.setLocale('en-GB');
    voice.startListening(listener());
    expect(latest().lang).toBe('en-GB');
  });

  it('is unsupported cleanly when the API is missing', () => {
    const { win } = fakeVoiceWindow({ recognition: false, synthesis: false });
    const voice = new BrowserVoiceProvider(win);
    expect(voice.supportsRecognition()).toBe(false);
    expect(voice.startListening(listener())).toBe(false);
    expect(voice.speak('hello')).toBe(false);
  });
});

describe('BrowserVoiceProvider read-aloud', () => {
  it('reports speaking/idle transitions and stops reading before the mic opens', () => {
    const { win, spoken } = fakeVoiceWindow();
    const voice = new BrowserVoiceProvider(win);
    const states: string[] = [];
    voice.onSpeechStateChange((s) => states.push(s));

    expect(voice.speak('Tell me about your approach.')).toBe(true);
    spoken[0].onstart?.();
    expect(voice.speechState()).toBe('speaking');

    voice.startListening(listener());
    expect(voice.speechState()).toBe('idle');
    expect(states).toEqual(['speaking', 'idle']);
  });

  it('does not read aloud while dictation is live (the mic would transcribe it)', () => {
    const { win, spoken } = fakeVoiceWindow();
    const voice = new BrowserVoiceProvider(win);
    voice.startListening(listener());
    expect(voice.speak('interviewer reply')).toBe(false);
    expect(spoken).toHaveLength(0);
  });

  it('surfaces a real synthesis failure as an error state', () => {
    const { win, spoken } = fakeVoiceWindow();
    const voice = new BrowserVoiceProvider(win);
    voice.speak('hello');
    spoken[0].onerror?.({ error: 'synthesis-failed' });
    expect(voice.speechState()).toBe('error');
  });
});
