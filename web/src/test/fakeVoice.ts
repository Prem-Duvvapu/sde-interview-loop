import type { SpeechRecognitionLike, VoiceWindow } from '../voice/VoiceProvider';

type ResultEvent = Parameters<NonNullable<SpeechRecognitionLike['onresult']>>[0];

/** A controllable stand-in for the browser's SpeechRecognition. */
export class FakeRecognition implements SpeechRecognitionLike {
  static instances: FakeRecognition[] = [];
  continuous = false;
  interimResults = false;
  lang = '';
  onresult: SpeechRecognitionLike['onresult'] = null;
  onend: SpeechRecognitionLike['onend'] = null;
  onerror: SpeechRecognitionLike['onerror'] = null;
  started = false;
  stopped = false;
  aborted = false;

  constructor() {
    FakeRecognition.instances.push(this);
  }
  start() {
    this.started = true;
  }
  stop() {
    this.stopped = true;
  }
  abort() {
    this.aborted = true;
  }

  /** Simulates the browser delivering results; `final` segments first, then interim. */
  emit(final: string[], interim: string[] = []) {
    const results = [
      ...final.map((t) => ({ isFinal: true, 0: { transcript: t } })),
      ...interim.map((t) => ({ isFinal: false, 0: { transcript: t } })),
    ];
    this.onresult?.({ results } as unknown as ResultEvent);
  }
  fail(error: string) {
    this.onerror?.({ error });
  }
  end() {
    this.onend?.();
  }
}

export class FakeUtterance {
  lang = '';
  rate = 1;
  onstart: (() => void) | null = null;
  onend: (() => void) | null = null;
  onerror: ((e: { error?: string }) => void) | null = null;
  text: string;
  constructor(text: string) {
    this.text = text;
  }
}

export function fakeVoiceWindow(opts: { recognition?: boolean; synthesis?: boolean } = {}) {
  FakeRecognition.instances = [];
  const spoken: FakeUtterance[] = [];
  const synth = {
    cancelled: 0,
    speak(u: FakeUtterance) {
      spoken.push(u);
    },
    cancel() {
      this.cancelled += 1;
      const last = spoken[spoken.length - 1];
      last?.onerror?.({ error: 'interrupted' });
    },
  };
  const win = {
    navigator: { language: 'en-IN' },
    ...(opts.recognition === false ? {} : { SpeechRecognition: FakeRecognition }),
    ...(opts.synthesis === false ? {} : { speechSynthesis: synth, SpeechSynthesisUtterance: FakeUtterance }),
  } as unknown as VoiceWindow;
  return { win, spoken, synth, latest: () => FakeRecognition.instances[FakeRecognition.instances.length - 1] };
}
