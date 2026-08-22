package com.premd.interviewloop.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * This is the foundation — actual LLM integration is wired in subsequent phases
 * when interviewer modules are built.
 */
@Component
public class InterviewWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(InterviewWebSocketHandler.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final FrameCodec frameCodec;

    /** Active WebSocket sessions keyed by session ID. */
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    public InterviewWebSocketHandler(FrameCodec frameCodec) {
        this.frameCodec = frameCodec;
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

        // Acknowledge receipt — actual LLM call will be added when interviewer modules exist
        sendFrame(session, frameCodec.acknowledgeTurn(roundId));

        // TODO: Wire through session manager → interviewer module → LLM provider → stream back
        // For now, echo back a placeholder to prove the transport works
        sendFrame(session, frameCodec.textDelta(
                "Turn received. Interviewer modules will be wired in Phase 2+."));
        sendFrame(session, frameCodec.turnComplete(roundId));
    }

    private void handleStartRound(WebSocketSession session, JsonNode frame) throws IOException {
        Long roundId = frame.has("roundId") ? frame.get("roundId").asLong() : null;
        if (roundId == null) {
            sendFrame(session, frameCodec.error("roundId is required"));
            return;
        }

        sendFrame(session, frameCodec.roundStarted(roundId));
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
