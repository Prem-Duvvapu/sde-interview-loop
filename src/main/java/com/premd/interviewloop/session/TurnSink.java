package com.premd.interviewloop.session;

import com.premd.interviewloop.domain.SessionRound;
import com.premd.interviewloop.domain.enums.RoundPhase;

import java.util.List;
import java.util.Map;

/**
 * Where a turn's output goes as it is produced.
 *
 * <p>Deliberately not a WebSocket: the orchestrator has no business knowing how the candidate is
 * connected, and a plain interface makes the whole turn loop testable without a socket, a browser
 * or a live provider.
 */
public interface TurnSink {

    /** A chunk of interviewer prose, as it streams. */
    void textDelta(String text);

    /** A control call the model made. Emitted whether or not the backend honours it. */
    void toolCall(String name, String id, Map<String, Object> arguments);

    /** The backend accepted a phase transition. Not emitted when one is rejected. */
    void phaseAdvanced(RoundPhase phase);

    /** The round ended. */
    void roundCompleted(Long roundId);

    /**
     * The next enabled full-loop round has a private panel handoff and may now be started.
     * Defaulted so non-WebSocket sinks keep working as the protocol gains this capability.
     */
    default void nextRoundReady(SessionRound nextRound, List<SessionRound> skippedRounds) {}

    /** Token usage and estimated cost for the turn. */
    void usage(int inputTokens, int outputTokens, int cacheReadTokens, double costUsd);

    /** The turn is finished and the candidate may respond. */
    void turnComplete(Long roundId);

    /** Something went wrong. The turn is over. */
    void error(String message);

    /** A sink that discards everything — useful in tests and for replaying without a client. */
    static TurnSink noop() {
        return new TurnSink() {
            @Override public void textDelta(String text) {}
            @Override public void toolCall(String name, String id, Map<String, Object> arguments) {}
            @Override public void phaseAdvanced(RoundPhase phase) {}
            @Override public void roundCompleted(Long roundId) {}
            @Override public void usage(int in, int out, int cacheRead, double cost) {}
            @Override public void turnComplete(Long roundId) {}
            @Override public void error(String message) {}
        };
    }
}
