import { describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { Composer } from './Composer';
import { BrowserVoiceProvider } from '../voice/VoiceProvider';
import { fakeVoiceWindow } from '../test/fakeVoice';

function setup(overrides: Partial<Parameters<typeof Composer>[0]> = {}) {
  const fake = fakeVoiceWindow();
  const voice = new BrowserVoiceProvider(fake.win);
  const onSend = vi.fn<(text: string) => boolean>(() => true);
  const props = { disabled: false, disabledReason: null, awaitingReply: false, onSend, voice, draftKey: 'draft.round.1', ...overrides };
  const view = render(<Composer {...props} />);
  const input = screen.getByLabelText('Your answer', { selector: 'textarea' }) as HTMLTextAreaElement;
  return { ...fake, voice, onSend: props.onSend, input, view, props };
}

const sendButton = () => screen.getByRole('button', { name: /send|waiting/i });

describe('Composer send outcome', () => {
  it('regression: a refused send keeps the draft', () => {
    const { input, onSend } = setup({ onSend: vi.fn(() => false) });
    fireEvent.change(input, { target: { value: 'Use a hash map keyed by value' } });
    fireEvent.click(sendButton());
    expect(onSend).toHaveBeenCalledWith('Use a hash map keyed by value');
    expect(input.value).toBe('Use a hash map keyed by value');
    expect(screen.getByText(/Not sent/)).toBeTruthy();
  });

  it('clears the draft only after a successful send', () => {
    const { input } = setup();
    fireEvent.change(input, { target: { value: 'answer' } });
    fireEvent.click(sendButton());
    expect(input.value).toBe('');
  });

  it('blocks a second submission while a reply is pending but keeps the draft editable', () => {
    const { input, onSend } = setup({ awaitingReply: true });
    fireEvent.change(input, { target: { value: 'next thought' } });
    expect((sendButton() as HTMLButtonElement).disabled).toBe(true);
    fireEvent.keyDown(input, { key: 'Enter', ctrlKey: true });
    expect(onSend).not.toHaveBeenCalled();
    expect(input.disabled).toBe(false);
  });

  it('keeps the textarea editable while disconnected', () => {
    const { input } = setup({ disabled: true, disabledReason: 'Connecting…' });
    expect(input.disabled).toBe(false);
    expect((sendButton() as HTMLButtonElement).disabled).toBe(true);
  });

  it('restores an unsent draft for the same round after a remount', () => {
    const first = setup();
    fireEvent.change(first.input, { target: { value: 'half-written idea' } });
    first.view.unmount();
    const second = setup();
    expect(second.input.value).toBe('half-written idea');
  });
});

describe('Composer dictation lifecycle', () => {
  it('regression: a late recognition result after Send cannot refill the composer', () => {
    const { input, latest } = setup();
    fireEvent.change(input, { target: { value: 'I would' } });
    fireEvent.click(screen.getByRole('button', { name: 'Use mic' }));
    const rec = latest();
    const captured = rec.onresult;
    act(() => rec.emit(['sort the intervals']));
    expect(input.value).toBe('I would sort the intervals');

    fireEvent.click(sendButton());
    expect(input.value).toBe('');
    // The browser delivers the final result it was holding, via the handler it captured.
    act(() => captured?.({ results: [{ isFinal: true, 0: { transcript: 'sort the intervals first' } }] } as never));
    expect(input.value).toBe('');
  });

  it('manual editing during dictation stops it and keeps the typed text', () => {
    const { input, latest } = setup();
    fireEvent.click(screen.getByRole('button', { name: 'Use mic' }));
    const rec = latest();
    act(() => rec.emit([], ['partial']));
    fireEvent.change(input, { target: { value: 'my own words' } });
    act(() => rec.emit(['partial words']));
    expect(input.value).toBe('my own words');
    expect(rec.aborted).toBe(true);
    expect(screen.getByRole('button', { name: 'Use mic' })).toBeTruthy();
  });

  it('unmounting (leaving or switching rounds) cancels recognition', () => {
    const { view, latest } = setup();
    fireEvent.click(screen.getByRole('button', { name: 'Use mic' }));
    view.unmount();
    expect(latest().aborted).toBe(true);
  });

  it('shows a retryable message when microphone permission is denied', () => {
    const { latest } = setup();
    fireEvent.click(screen.getByRole('button', { name: 'Use mic' }));
    act(() => latest().fail('not-allowed'));
    expect(screen.getByText(/permission was not granted/)).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Use mic' })).toBeTruthy();
  });

  it('disables the mic button when recognition is unsupported', () => {
    const fake = fakeVoiceWindow({ recognition: false });
    render(<Composer disabled={false} disabledReason={null} awaitingReply={false} onSend={() => true} voice={new BrowserVoiceProvider(fake.win)} />);
    expect((screen.getByRole('button', { name: 'Use mic' }) as HTMLButtonElement).disabled).toBe(true);
  });
});
