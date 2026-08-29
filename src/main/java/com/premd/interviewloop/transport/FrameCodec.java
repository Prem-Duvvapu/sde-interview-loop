package com.premd.interviewloop.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premd.interviewloop.domain.SessionRound;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON frame encoding for WebSocket messages.
 * Typed message envelopes with consistent structure.
 */
@Component
public class FrameCodec {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String pong() {
        return encode("pong", Map.of());
    }

    public String error(String message) {
        return encode("error", Map.of("message", message));
    }

    public String acknowledgeTurn(Long roundId) {
        return encode("turn_ack", Map.of("roundId", roundId));
    }

    public String textDelta(String text) {
        return encode("text_delta", Map.of("text", text));
    }

    public String toolCall(String name, String id, Map<String, Object> arguments) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", name);
        data.put("id", id);
        data.put("arguments", arguments);
        return encode("tool_call", data);
    }

    public String phaseAdvanced(String phase) {
        return encode("phase_advanced", Map.of("phase", phase));
    }

    public String turnComplete(Long roundId) {
        return encode("turn_complete", Map.of("roundId", roundId));
    }

    public String roundStarted(Long roundId) {
        return encode("round_started", Map.of("roundId", roundId));
    }

    public String roundCompleted(Long roundId) {
        return encode("round_completed", Map.of("roundId", roundId));
    }

    public String nextRoundReady(SessionRound round, List<SessionRound> skippedRounds) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("round", roundPayload(round));
        data.put("skippedRoundOrdinals", skippedRounds.stream().map(SessionRound::getOrdinal).toList());
        return encode("next_round_ready", data);
    }

    public String usage(int inputTokens, int outputTokens, int cacheReadTokens, double costUsd) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inputTokens", inputTokens);
        data.put("outputTokens", outputTokens);
        data.put("cacheReadTokens", cacheReadTokens);
        data.put("costUsd", costUsd);
        return encode("usage", data);
    }

    private String encode(String type, Map<String, Object> data) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", type);
        frame.putAll(data);
        try {
            return objectMapper.writeValueAsString(frame);
        } catch (JsonProcessingException e) {
            return "{\"type\":\"error\",\"message\":\"Frame encoding failed\"}";
        }
    }

    private Map<String, Object> roundPayload(SessionRound round) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", round.getId());
        data.put("ordinal", round.getOrdinal());
        data.put("moduleType", round.getModuleType().getValue());
        data.put("phase", round.getPhase().name());
        data.put("status", round.getStatus().name());
        data.put("difficultyTarget", round.getDifficultyTarget());
        data.put("plannedDurationSec", round.getPlannedDurationSec());
        data.put("interviewerProvider", round.getInterviewerProvider());
        data.put("interviewerModel", round.getInterviewerModel());
        return data;
    }
}
