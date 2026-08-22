package com.premd.interviewloop.interviewer;

import com.premd.interviewloop.domain.enums.RoundPhase;

import java.util.Locale;
import java.util.Map;

/**
 * A parsed control tool call from the interviewer model.
 *
 * <p>Parsing is deliberately lenient about shape and strict about meaning: models get argument
 * types subtly wrong (a score arriving as {@code "4"} rather than {@code 4}) often enough that
 * rejecting the call outright would lose real signal. Anything that cannot be understood becomes
 * {@link Malformed} and is surfaced rather than silently dropped.
 */
public sealed interface ControlCall {

    /** An observation about one rubric dimension, recorded as the round happens. */
    record RecordSignal(String dimension, int score, String confidence, String evidence)
            implements ControlCall {}

    /** A request to move the round forward. The state machine may still refuse it. */
    record AdvancePhase(RoundPhase targetPhase, String rationale) implements ControlCall {}

    /** A request to escalate how much help is being given. */
    record SetHintLevel(int level, String rationale) implements ControlCall {}

    /** A request to end the round. */
    record EndRound(String reason) implements ControlCall {}

    /** A call that could not be interpreted — kept so it shows up instead of vanishing. */
    record Malformed(String toolName, String problem, Map<String, Object> arguments)
            implements ControlCall {}

    static ControlCall parse(String toolName, Map<String, Object> args) {
        Map<String, Object> a = args == null ? Map.of() : args;
        try {
            return switch (toolName) {
                case InterviewerTools.RECORD_SIGNAL -> new RecordSignal(
                        requireString(a, "dimension"),
                        clamp(requireInt(a, "score"), 1, 5),
                        optString(a, "confidence", "medium").toLowerCase(Locale.ROOT),
                        optString(a, "evidence", ""));
                case InterviewerTools.ADVANCE_PHASE -> new AdvancePhase(
                        parsePhase(requireString(a, "target_phase")),
                        optString(a, "rationale", ""));
                case InterviewerTools.SET_HINT_LEVEL -> new SetHintLevel(
                        clamp(requireInt(a, "level"), 0, 3),
                        optString(a, "rationale", ""));
                case InterviewerTools.END_ROUND -> new EndRound(
                        optString(a, "reason", ""));
                default -> new Malformed(toolName, "Unknown tool", a);
            };
        } catch (IllegalArgumentException e) {
            return new Malformed(toolName, e.getMessage(), a);
        }
    }

    private static RoundPhase parsePhase(String raw) {
        try {
            return RoundPhase.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Not a known phase: " + raw);
        }
    }

    private static String requireString(Map<String, Object> a, String key) {
        Object v = a.get(key);
        if (v == null || v.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        return v.toString();
    }

    private static String optString(Map<String, Object> a, String key, String fallback) {
        Object v = a.get(key);
        return v == null || v.toString().isBlank() ? fallback : v.toString();
    }

    private static int requireInt(Map<String, Object> a, String key) {
        Object v = a.get(key);
        if (v == null) {
            throw new IllegalArgumentException("Missing required argument: " + key);
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            // Models routinely send numbers as strings; a round of signal is worth more
            // than a strict type check.
            return (int) Math.round(Double.parseDouble(v.toString().trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Argument " + key + " is not a number: " + v);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
