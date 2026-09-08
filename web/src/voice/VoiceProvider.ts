
/**
 * Browser voice boundary (DM-1).
 *
 * The interview service never receives microphone audio. Recognition happens in the browser and
 * produces ordinary editable candidate text; synthesis reads only a completed interviewer turn.
 * Keeping this small boundary means a future BYO-key voice provider can replace it without
 * changing the interview protocol or introducing a paid dependency.
 */

export interface RecognitionUpdate {
  finalText: string;
  interimText: string;
}

export interface RecognitionListener {
  onUpdate(update: RecognitionUpdate): void;
  onEnd(): void;
  onError(message: string): void;
}

export interface VoiceProvider {
  supportsRecognition(): boolean;
  supportsSynthesis(): boolean;
  startListening(listener: RecognitionListener): boolean;
  stopListening(): void;
  speak(text: string): void;
  cancelSpeech(): void;
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

interface SpeechRecognitionLike {
  continuous: boolean;
  interimResults: boolean;
  lang: string;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onend: (() => void) | null;
  onerror: ((event: SpeechRecognitionErrorEventLike) => void) | null;
  start(): void;
  stop(): void;
}

type SpeechRecognitionConstructor = new () => SpeechRecognitionLike;

type VoiceWindow = Window & typeof globalThis & {
  SpeechRecognition?: SpeechRecognitionConstructor;
  webkitSpeechRecognition?: SpeechRecognitionConstructor;
};

/** Web Speech API implementation. Safari exposes recognition with a webkit prefix. */
export class BrowserVoiceProvider implements VoiceProvider {
  private recognition: SpeechRecognitionLike | null = null;
  private readonly recognitionConstructor: SpeechRecognitionConstructor | undefined;
  private readonly browser: VoiceWindow;

  constructor(browser: VoiceWindow = window as VoiceWindow) {
    this.browser = browser;
    this.recognitionConstructor = browser.SpeechRecognition ?? browser.webkitSpeechRecognition;
  }

  supportsRecognition(): boolean {
    return this.recognitionConstructor !== undefined;
  }

  supportsSynthesis(): boolean {
    return 'speechSynthesis' in this.browser && typeof SpeechSynthesisUtterance !== 'undefined';
  }

  startListening(listener: RecognitionListener): boolean {
    if (!this.recognitionConstructor) return false;

    this.stopListening();
    const recognition = new this.recognitionConstructor();
    recognition.continuous = true;
    recognition.interimResults = true;
    recognition.lang = this.browser.navigator.language || 'en-IN';
    recognition.onresult = (event) => {
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
    recognition.onerror = (event) => listener.onError(recognitionErrorMessage(event.error));
    recognition.onend = () => {
      if (this.recognition === recognition) this.recognition = null;
      listener.onEnd();
    };
    this.recognition = recognition;
    try {
      recognition.start();
      return true;
    } catch {
      this.recognition = null;
      listener.onError('The microphone could not start. Check browser microphone permission.');
      return false;
    }
  }

  stopListening(): void {
    this.recognition?.stop();
  }

  speak(text: string): void {
    if (!this.supportsSynthesis() || !text.trim()) return;
    this.browser.speechSynthesis.cancel();
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = this.browser.navigator.language || 'en-IN';
    utterance.rate = 1;
    this.browser.speechSynthesis.speak(utterance);
  }

  cancelSpeech(): void {
    if (this.supportsSynthesis()) this.browser.speechSynthesis.cancel();
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
    case 'service-not-allowed': return 'Microphone permission was not granted.';
    case 'no-speech': return 'No speech was detected. Try again when you are ready.';
    case 'audio-capture': return 'No microphone is available to the browser.';
    case 'network': return 'Speech recognition needs a browser speech service connection.';
    default: return 'Speech recognition stopped unexpectedly. Try again.';
  }
}
