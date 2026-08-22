package com.premd.interviewloop.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premd.interviewloop.interviewer.ModuleRegistry;
import com.premd.interviewloop.session.TurnOrchestrator;
import com.premd.interviewloop.session.TurnSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for interview turns.
 *
 * Per candidate turn:
 * 1. Client sends a turn: chat text + current editor buffer / diagram graph
 * 2. Backend assembles request in cache-stable order
 * 3. LLM call, streamed token-by-token back over the same socket
 * 4. Model returns prose + tool calls (record_signal, advance_phase, etc.)
 * 5. Backend persists turn, applies/rejects control calls, updates round state
 *
 * The turn itself is run by {@link TurnOrchestrator}; this class only moves frames. When a round
 * asks for a module that Phases 2–5 have not built yet, it says so plainly rather than pretending
 * to conduct an interview.
 */
@Component
public class InterviewWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(InterviewWebSocketHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FrameCodec frameCodec;
    private final TurnOrchestrator turnOrchestrator;

    /** Active WebSocket sessions keyed by session ID. */
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    public InterviewWebSocketHandler(FrameCodec frameCodec, TurnOrchestrator turnOrchestrator) {
        this.frameCodec = frameCodec;
        this.turnOrchestrator = turnOrchestrator;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        activeSessions.put(session.getId(), session);
        log.info("WebSocket connected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        try {
            JsonNode frame = objectMapper.readTree(message.getPayload());
            String type = frame.has("type") ? frame.get("type").asText() : "unknown";

            switch (type) {
                case "candidate_turn" -> handleCandidateTurn(session, frame);
                case "start_round" -> handleStartRound(session, frame);
                case "ping" -> sendFrame(session, frameCodec.pong());
                default -> sendFrame(session, frameCodec.error("Unknown message type: " + type));
            }
        } catch (Exception e) {
            log.error("Error handling WebSocket message", e);
            sendFrame(session, frameCodec.error(e.getMessage()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        activeSessions.remove(session.getId());
        log.info("WebSocket disconnected: {} ({})", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket transport error for session {}", session.getId(), exception);
    }

    private void handleCandidateTurn(WebSocketSession session, JsonNode frame) throws IOException {
        String text = frame.has("text") ? frame.get("text").asText() : "";
        String artifact = frame.has("artifact") ? frame.get("artifact").asText() : null;
        Long roundId = frame.has("roundId") ? frame.get("roundId").asLong() : null;

        if (roundId == null) {
            sendFrame(session, frameCodec.error("roundId is required"));
            return;
        }

        log.debug("Candidate turn for round {}: {} chars, artifact: {}",
                roundId, text.length(), artifact != null ? artifact.length() + " chars" : "none");

        sendFrame(session, frameCodec.acknowledgeTurn(roundId));

        TurnSink sink = new WebSocketTurnSink(session, frameCodec);
        try {
            turnOrchestrator.handleCandidateTurn(roundId, text, artifact, sink);
        } catch (ModuleRegistry.ModuleNotAvailableException e) {
            sink.error(notImplementedMessage(e));
            sink.turnComplete(roundId);
        } catch (Exception e) {
            log.error("Turn failed for round {}", roundId, e);
            sink.error(e.getMessage());
            sink.turnComplete(roundId);
        }
    }

    private void handleStartRound(WebSocketSession session, JsonNode frame) throws IOException {
        Long roundId = frame.has("roundId") ? frame.get("roundId").asLong() : null;
        if (roundId == null) {
            sendFrame(session, frameCodec.error("roundId is required"));
            return;
        }

        TurnSink sink = new WebSocketTurnSink(session, frameCodec);
        try {
            turnOrchestrator.beginRound(roundId, sink);
            sendFrame(session, frameCodec.roundStarted(roundId));
        } catch (ModuleRegistry.ModuleNotAvailableException e) {
            sink.error(notImplementedMessage(e));
        } catch (Exception e) {
            log.error("Could not start round {}", roundId, e);
            sink.error(e.getMessage());
        }
    }

    private String notImplementedMessage(ModuleRegistry.ModuleNotAvailableException e) {
        return "The " + e.getModuleType().getValue() + " interviewer has not been built yet. "
                + "Session setup, transport and persistence all work; the module lands in a later phase.";
    }

    public void sendFrame(WebSocketSession session, String json) throws IOException {
        if (session.isOpen()) {
            session.sendMessage(new TextMessage(json));
        }
    }

    /** Send a frame to a specific session by its WebSocket session ID. */
    public void sendToSession(String wsSessionId, String json) throws IOException {
        WebSocketSession session = activeSessions.get(wsSessionId);
        if (session != null && session.isOpen()) {
            session.sendMessage(new TextMessage(json));
        }
    }
}
