import { readStored, writeStored } from '../lib/storage';

/**
 * Browser voice boundary (DM-1).
 *
 * The interview service never receives microphone audio. Recognition happens in the browser and
 * produces ordinary editable candidate text; synthesis reads only a completed interviewer turn.
 * Keeping this small boundary means a future BYO-key voice provider can replace it without
 * changing the interview protocol or introducing a paid dependency.
 *
 * Note that "in the browser" is not "on the device": Chrome's Web Speech recognition, for
 * example, sends audio to the browser vendor's speech service. What this app guarantees is only
 * that audio never reaches *its own* backend.
 *
 * Callback ownership: each `startListening` creates a session. `cancelListening` detaches that
 * session immediately, so a recognition result that arrives late (browsers routinely deliver a
 * final result after `stop()`) can never overwrite a draft that was already sent, a newer
 * draft, or a composer that has unmounted.
 */

export interface RecognitionUpdate {
  finalText: string;
  interimText: string;
}

export interface RecognitionListener {
  onUpdate(update: RecognitionUpdate): void;
  onEnd(): void;
  onError(message: string, code: string): void;
}

export type SpeechState = 'idle' | 'speaking' | 'error';

export interface VoiceProvider {
  supportsRecognition(): boolean;
  supportsSynthesis(): boolean;
  /** Starts a new dictation session, cancelling read-aloud and any previous session. */
  startListening(listener: RecognitionListener): boolean;
  /** Graceful stop: the pending final result for the current utterance may still arrive. */
  stopListening(): void;
  /** Hard stop: detaches the session; no further callback from it will fire. */
  cancelListening(): void;
  isListening(): boolean;
  /** Returns false when nothing was spoken (unsupported, empty, or the mic is live). */
  speak(text: string): boolean;
  cancelSpeech(): void;
  speechState(): SpeechState;
  onSpeechStateChange(listener: (state: SpeechState) => void): () => void;
  locale(): string;
  setLocale(locale: string): void;
}

interface SpeechRecognitionAlternativeLike {
  transcript: string;
}

interface SpeechRecognitionResultLike {
  isFinal: boolean;
  0: SpeechRecognitionAlternativeLike;
}

interface SpeechRecognitionEventLike {
  results: ArrayLike<SpeechRecognitionResultLike>;
}

interface SpeechRecognitionErrorEventLike {
  error: string;
}

export interface SpeechRecognitionLike {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onend: (() => void) | null;
  onerror: ((event: SpeechRecognitionErrorEventLike) => void) | null;
  start(): void;
  stop(): void;
  abort?: () => void;
}

type SpeechRecognitionConstructor = new () => SpeechRecognitionLike;

export type VoiceWindow = Window & typeof globalThis & {
  SpeechRecognition?: SpeechRecognitionConstructor;
  webkitSpeechRecognition?: SpeechRecognitionConstructor;
};

interface ListeningSession {
  recognition: SpeechRecognitionLike;
  detached: boolean;
}

export const VOICE_LOCALE_KEY = 'voice.locale';

/** Locales offered in Settings. The browser may support more; these are the useful defaults. */
export const VOICE_LOCALES: { id: string; label: string }[] = [
  { id: 'en-IN', label: 'English (India)' },
  { id: 'en-US', label: 'English (US)' },
  { id: 'en-GB', label: 'English (UK)' },
];

/** Web Speech API implementation. Safari exposes recognition with a webkit prefix. */
export class BrowserVoiceProvider implements VoiceProvider {
  private session: ListeningSession | null = null;
  private readonly recognitionConstructor: SpeechRecognitionConstructor | undefined;
  private readonly browser: VoiceWindow;
  private state: SpeechState = 'idle';
  private readonly speechListeners = new Set<(state: SpeechState) => void>();
  private currentLocale: string;

  constructor(browser: VoiceWindow = window as VoiceWindow) {
    this.browser = browser;
    this.recognitionConstructor = browser.SpeechRecognition ?? browser.webkitSpeechRecognition;
    this.currentLocale = readStored(VOICE_LOCALE_KEY) || browser.navigator?.language || 'en-IN';
  }

  supportsRecognition(): boolean {
    return this.recognitionConstructor !== undefined;
  }

  supportsSynthesis(): boolean {
    return 'speechSynthesis' in this.browser && typeof this.browser.SpeechSynthesisUtterance !== 'undefined';
  }

  locale(): string {
    return this.currentLocale;
  }

  setLocale(locale: string): void {
    this.currentLocale = locale;
    writeStored(VOICE_LOCALE_KEY, locale);
  }

  isListening(): boolean {
    return this.session !== null && !this.session.detached;
  }

  startListening(listener: RecognitionListener): boolean {
    if (!this.recognitionConstructor) return false;

    // Never let the microphone hear the interviewer being read aloud.
    this.cancelSpeech();
    this.cancelListening();

    let recognition: SpeechRecognitionLike;
    try {
      recognition = new this.recognitionConstructor();
    } catch {
      listener.onError('Speech recognition could not start in this browser.', 'construct');
      return false;
    }
    const session: ListeningSession = { recognition, detached: false };
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = this.currentLocale;
    recognition.onresult = (event) => {
      if (session.detached) return;
      const finalParts: string[] = [];
      const interimParts: string[] = [];
      for (let index = 0; index < event.results.length; index += 1) {
        const result = event.results[index];
        const transcript = result?.[0]?.transcript?.trim();
        if (!transcript) continue;
        (result.isFinal ? finalParts : interimParts).push(transcript);
      }
      listener.onUpdate({ finalText: finalParts.join(' '), interimText: interimParts.join(' ') });
    };
    recognition.onerror = (event) => {
      if (session.detached) return;
      // 'aborted' is what our own cancel produces; it is not a user-facing failure.
      if (event.error === 'aborted') return;
      listener.onError(recognitionErrorMessage(event.error), event.error);
    };
    recognition.onend = () => {
      if (this.session === session) this.session = null;
      if (session.detached) return;
      session.detached = true;
      listener.onEnd();
    };
    this.session = session;
    try {
      recognition.start();
      return true;
    } catch {
      session.detached = true;
      this.session = null;
      listener.onError('The microphone could not start. Check browser microphone permission.', 'start');
      return false;
    }
  }

  stopListening(): void {
    const session = this.session;
    if (!session || session.detached) return;
    try {
      session.recognition.stop();
    } catch {
      this.cancelListening();
    }
  }

  cancelListening(): void {
    const session = this.session;
    if (!session) return;
    session.detached = true;
    this.session = null;
    const { recognition } = session;
    recognition.onresult = null;
    recognition.onerror = null;
    try {
      if (recognition.abort) recognition.abort();
      else recognition.stop();
    } catch {
      /* already stopped */
    }
  }

  speak(text: string): boolean {
    if (!this.supportsSynthesis() || !text.trim()) return false;
    // Reading aloud while dictating would transcribe the interviewer into the candidate's answer.
    if (this.isListening()) return false;
    const synth = this.browser.speechSynthesis;
    synth.cancel();
    const utterance = new this.browser.SpeechSynthesisUtterance(text);
    utterance.lang = this.currentLocale;
    utterance.rate = 1;
    utterance.onstart = () => this.setState('speaking');
    utterance.onend = () => this.setState('idle');
    utterance.onerror = (event: { error?: string }) => {
      // cancel() reports 'interrupted'/'canceled'; only a real failure is an error state.
      this.setState(event.error === 'interrupted' || event.error === 'canceled' ? 'idle' : 'error');
    };
    synth.speak(utterance);
    return true;
  }

  cancelSpeech(): void {
    if (this.supportsSynthesis()) this.browser.speechSynthesis.cancel();
    if (this.state !== 'idle') this.setState('idle');
  }

  speechState(): SpeechState {
    return this.state;
  }

  onSpeechStateChange(listener: (state: SpeechState) => void): () => void {
    this.speechListeners.add(listener);
    return () => this.speechListeners.delete(listener);
  }

  private setState(next: SpeechState): void {
    if (this.state === next) return;
    this.state = next;
    for (const listener of this.speechListeners) listener(next);
  }
}

export function plainTextForSpeech(markdown: string): string {
  return markdown
    .replace(/```[\s\S]*?```/g, ' Code example omitted. ')
    .replace(/`([^`]+)`/g, '$1')
    .replace(/[*_#>[\]()]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function recognitionErrorMessage(error: string): string {
  switch (error) {
    case 'not-allowed':
    case 'service-not-allowed':
      return 'Microphone permission was not granted. Allow it in the browser address bar, then press Use mic again.';
    case 'no-speech':
      return 'No speech was detected. Press Use mic again when you are ready.';
    case 'audio-capture':
      return 'No microphone is available to the browser.';
    case 'network':
      return 'Speech recognition needs the browser’s speech service, which could not be reached. You can keep typing.';
    case 'language-not-supported':
      return 'This browser cannot recognise the selected voice language. Choose another in Settings.';
    default:
      return 'Speech recognition stopped unexpectedly. Press Use mic to try again.';
  }
}
