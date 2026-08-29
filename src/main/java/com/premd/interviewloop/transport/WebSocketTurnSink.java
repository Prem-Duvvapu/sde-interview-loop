package com.premd.interviewloop.transport;

import com.premd.interviewloop.domain.enums.RoundPhase;
import com.premd.interviewloop.domain.SessionRound;
import com.premd.interviewloop.session.TurnSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Adapts a {@link TurnSink} onto a WebSocket connection.
 *
 * <p>A send that fails must not abort the turn: the model response has already been paid for, and
 * the transcript is persisted regardless, so a candidate whose connection drops mid-turn can
 * reconnect and replay rather than losing the turn entirely.
 */
public class WebSocketTurnSink implements TurnSink {

    private static final Logger log = LoggerFactory.getLogger(WebSocketTurnSink.class);

    private final WebSocketSession session;
    private final FrameCodec codec;

    public WebSocketTurnSink(WebSocketSession session, FrameCodec codec) {
        this.session = session;
        this.codec = codec;
    }

    @Override
    public void textDelta(String text) {
        send(codec.textDelta(text));
    }

    @Override
    public void toolCall(String name, String id, Map<String, Object> arguments) {
        send(codec.toolCall(name, id, arguments));
    }

    @Override
    public void phaseAdvanced(RoundPhase phase) {
        send(codec.phaseAdvanced(phase.name()));
    }

    @Override
    public void roundCompleted(Long roundId) {
        send(codec.roundCompleted(roundId));
    }

    @Override
    public void nextRoundReady(SessionRound nextRound, List<SessionRound> skippedRounds) {
        send(codec.nextRoundReady(nextRound, skippedRounds));
    }

    @Override
    public void usage(int inputTokens, int outputTokens, int cacheReadTokens, double costUsd) {
        send(codec.usage(inputTokens, outputTokens, cacheReadTokens, costUsd));
    }

    @Override
    public void turnComplete(Long roundId) {
        send(codec.turnComplete(roundId));
    }

    @Override
    public void error(String message) {
        send(codec.error(message));
    }

    private void send(String json) {
        if (!session.isOpen()) {
            return;
        }
        try {
            // Spring's WebSocketSession is not safe for concurrent senders.
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException e) {
            log.warn("Dropped frame for session {}: {}", session.getId(), e.getMessage());
        }
    }
}
