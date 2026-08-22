import { useCallback, useEffect, useRef, useState } from 'react';
import { parseFrame, type OutboundFrame, type ParsedFrame } from './frames';

export type SocketStatus = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed';

export interface SocketState {
  status: SocketStatus;
  /** Consecutive failed connection attempts; 0 once connected. */
  attempts: number;
  /** ms until the next reconnect, or null when not waiting. */
  retryInMs: number | null;
  /** Round-trip time of the last ping/pong, in ms. */
  latencyMs: number | null;
}

const PING_INTERVAL_MS = 25_000;
const BASE_BACKOFF_MS = 750;
const MAX_BACKOFF_MS = 15_000;

function socketUrl(path: string): string {
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${window.location.host}${path}`;
}

/** Full jitter on an exponential backoff — avoids a tight retry loop against a dead backend. */
function backoffFor(attempt: number): number {
  const ceiling = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * 2 ** Math.min(attempt, 6));
  return Math.round(ceiling / 2 + Math.random() * (ceiling / 2));
}

export interface UseInterviewSocketOptions {
  /** Nothing connects until this is true — the socket opens when a round opens. */
  enabled: boolean;
  path?: string;
  onFrame: (parsed: ParsedFrame) => void;
  onOpen?: () => void;
  onClose?: (wasClean: boolean) => void;
}

export function useInterviewSocket({
  enabled,
  path = '/ws/interview',
  onFrame,
  onOpen,
  onClose,
}: UseInterviewSocketOptions) {
  const [state, setState] = useState<SocketState>({
    status: 'idle',
    attempts: 0,
    retryInMs: null,
    latencyMs: null,
  });

  // Callbacks live in refs so that re-rendering the parent never tears down the socket.
  const onFrameRef = useRef(onFrame);
  const onOpenRef = useRef(onOpen);
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onFrameRef.current = onFrame;
    onOpenRef.current = onOpen;
    onCloseRef.current = onClose;
  });

  const socketRef = useRef<WebSocket | null>(null);
  const retryTimerRef = useRef<number | null>(null);
  const countdownTimerRef = useRef<number | null>(null);
  const pingTimerRef = useRef<number | null>(null);
  const pingSentAtRef = useRef<number | null>(null);
  const attemptsRef = useRef(0);
  const disposedRef = useRef(false);

  const clearTimers = useCallback(() => {
    for (const ref of [retryTimerRef, countdownTimerRef, pingTimerRef]) {
      if (ref.current !== null) {
        window.clearInterval(ref.current);
        window.clearTimeout(ref.current);
        ref.current = null;
      }
    }
  }, []);

  const connect = useCallback(() => {
    if (disposedRef.current) return;
    if (socketRef.current && socketRef.current.readyState <= WebSocket.OPEN) return;

    setState((s) => ({
      ...s,
      status: attemptsRef.current === 0 ? 'connecting' : 'reconnecting',
      retryInMs: null,
    }));

    let ws: WebSocket;
    try {
      ws = new WebSocket(socketUrl(path));
    } catch {
      scheduleRetry();
      return;
    }
    socketRef.current = ws;

    ws.onopen = () => {
      if (disposedRef.current) {
        ws.close();
        return;
      }
      attemptsRef.current = 0;
      setState({ status: 'open', attempts: 0, retryInMs: null, latencyMs: null });
      onOpenRef.current?.();

      pingTimerRef.current = window.setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) {
          pingSentAtRef.current = performance.now();
          ws.send(JSON.stringify({ type: 'ping' } satisfies OutboundFrame));
        }
      }, PING_INTERVAL_MS);
    };

    ws.onmessage = (event) => {
      const payload = typeof event.data === 'string' ? event.data : '';
      const parsed = parseFrame(payload);
      if (parsed.kind === 'known' && parsed.frame.type === 'pong' && pingSentAtRef.current !== null) {
        const rtt = Math.round(performance.now() - pingSentAtRef.current);
        pingSentAtRef.current = null;
        setState((s) => ({ ...s, latencyMs: rtt }));
      }
      onFrameRef.current(parsed);
    };

    ws.onerror = () => {
      // A failed connection also fires onclose; the retry is scheduled there.
    };

    ws.onclose = (event) => {
      socketRef.current = null;
      if (pingTimerRef.current !== null) {
        window.clearInterval(pingTimerRef.current);
        pingTimerRef.current = null;
      }
      onCloseRef.current?.(event.wasClean);
      if (disposedRef.current) {
        setState((s) => ({ ...s, status: 'closed', retryInMs: null }));
        return;
      }
      scheduleRetry();
    };

    function scheduleRetry() {
      if (disposedRef.current) return;
      attemptsRef.current += 1;
      const delay = backoffFor(attemptsRef.current);
      const dueAt = Date.now() + delay;

      setState({
        status: 'reconnecting',
        attempts: attemptsRef.current,
        retryInMs: delay,
        latencyMs: null,
      });

      countdownTimerRef.current = window.setInterval(() => {
        const left = Math.max(0, dueAt - Date.now());
        setState((s) => (s.status === 'reconnecting' ? { ...s, retryInMs: left } : s));
      }, 250);

      retryTimerRef.current = window.setTimeout(() => {
        if (countdownTimerRef.current !== null) {
          window.clearInterval(countdownTimerRef.current);
          countdownTimerRef.current = null;
        }
        connect();
      }, delay);
    }
  }, [path]);

  useEffect(() => {
    if (!enabled) {
      setState({ status: 'idle', attempts: 0, retryInMs: null, latencyMs: null });
      return;
    }
    disposedRef.current = false;
    attemptsRef.current = 0;
    connect();

    return () => {
      disposedRef.current = true;
      clearTimers();
      const ws = socketRef.current;
      socketRef.current = null;
      if (ws && ws.readyState <= WebSocket.OPEN) ws.close(1000, 'client navigating away');
    };
  }, [enabled, connect, clearTimers]);

  /** Returns false when the socket is not open — callers surface that, they do not queue. */
  const send = useCallback((frame: OutboundFrame): boolean => {
    const ws = socketRef.current;
    if (!ws || ws.readyState !== WebSocket.OPEN) return false;
    try {
      ws.send(JSON.stringify(frame));
      return true;
    } catch {
      return false;
    }
  }, []);

  /** Cancel any pending backoff and try immediately. */
  const reconnectNow = useCallback(() => {
    clearTimers();
    attemptsRef.current = 0;
    connect();
  }, [clearTimers, connect]);

  return { ...state, send, reconnectNow };
}
