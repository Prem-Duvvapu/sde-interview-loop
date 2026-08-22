package com.premd.interviewloop.interviewer;

import com.premd.interviewloop.llm.LlmRequest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The control tools every interviewer module exposes to the model.
 *
 * <p>These are what make evaluation incremental rather than one end-of-round pass, and what
 * keep the round on rails: the model asks for a transition, the backend decides whether to
 * grant it.
 *
 * <p>Tool definitions are the first block of the cached prefix, so this list must be
 * <b>byte-stable</b> across turns — build it from constants, never from per-request data.
 */
public final class InterviewerTools {

    public static final String RECORD_SIGNAL = "record_signal";
    public static final String ADVANCE_PHASE = "advance_phase";
    public static final String SET_HINT_LEVEL = "set_hint_level";
    public static final String END_ROUND = "end_round";

    private static final List<LlmRequest.Tool> STANDARD = List.of(
            new LlmRequest.Tool(
                    RECORD_SIGNAL,
                    "Record an observation about the candidate's performance on one rubric dimension, "
                            + "as soon as you observe it. Do not wait until the end of the round. "
                            + "Every signal must quote or paraphrase what the candidate actually said or wrote.",
                    object(
                            props(
                                    prop("dimension", "string",
                                            "Rubric dimension this observation belongs to."),
                                    enumProp("score", "integer",
                                            "1 = well below the SDE-2 bar, 3 = at the bar, 5 = clearly above it.",
                                            List.of(1, 2, 3, 4, 5)),
                                    enumProp("confidence", "string",
                                            "How much evidence this is based on so far.",
                                            List.of("low", "medium", "high")),
                                    prop("evidence", "string",
                                            "The specific thing the candidate said or wrote that justifies this score.")
                            ),
                            List.of("dimension", "score", "confidence", "evidence"))),

            new LlmRequest.Tool(
                    ADVANCE_PHASE,
                    "Request that the round move to a later phase. The backend validates this against the "
                            + "module's phase sequence and will reject attempts to skip ahead or go back. "
                            + "Only ask once the current phase has genuinely produced its signal.",
                    object(
                            props(
                                    prop("target_phase", "string",
                                            "The phase to move to, e.g. CODING or COMPLEXITY."),
                                    prop("rationale", "string",
                                            "Why the current phase is finished.")
                            ),
                            List.of("target_phase", "rationale"))),

            new LlmRequest.Tool(
                    SET_HINT_LEVEL,
                    "Escalate how much help you are giving. 0 = none, 1 = a nudge toward the right area, "
                            + "2 = a concrete hint, 3 = substantial guidance. Escalate only after the candidate "
                            + "has been genuinely stuck, and record that you did — hints are scored.",
                    object(
                            props(
                                    enumProp("level", "integer",
                                            "New hint level. May only increase.",
                                            List.of(0, 1, 2, 3)),
                                    prop("rationale", "string",
                                            "What the candidate was stuck on.")
                            ),
                            List.of("level", "rationale"))),

            new LlmRequest.Tool(
                    END_ROUND,
                    "End the round. Use when the phase sequence is complete or the time budget is spent. "
                            + "Do not end early because the candidate is struggling — a struggling candidate "
                            + "still needs the remaining phases to generate signal.",
                    object(
                            props(
                                    prop("reason", "string",
                                            "Why the round is ending now.")
                            ),
                            List.of("reason")))
    );

    private InterviewerTools() {}

    /** The standard control tool set, shared by all modules. Immutable and byte-stable. */
    public static List<LlmRequest.Tool> standard() {
        return STANDARD;
    }

    // -- Schema helpers: plain maps, so no JSON-schema library leaks into the SPI --
    //
    // Every map here is a LinkedHashMap wrapped unmodifiable, never Map.of / Map.copyOf.
    // Those have unspecified iteration order, which would serialise these schemas
    // differently between JVM runs and silently break the cached prefix.

    private static Map<String, Object> object(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return Collections.unmodifiableMap(schema);
    }

    @SafeVarargs
    private static Map<String, Object> props(Map.Entry<String, Object>... entries) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : entries) {
            map.put(e.getKey(), e.getValue());
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map.Entry<String, Object> prop(String name, String type, String description) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", type);
        spec.put("description", description);
        return Map.entry(name, Collections.unmodifiableMap(spec));
    }

    private static Map.Entry<String, Object> enumProp(String name, String type, String description,
                                                      List<?> allowed) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", type);
        spec.put("description", description);
        spec.put("enum", List.copyOf(allowed));
        return Map.entry(name, Collections.unmodifiableMap(spec));
    }
}
