package com.premd.interviewloop.evaluation;

import com.premd.interviewloop.llm.LlmRequest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The single tool the evaluator uses to return a structured result. Tool use (not prose
 * parsing) is what makes the result auditable — a JSON blob the model chose not to produce
 * correctly fails loudly (see {@link RoundEvaluator}'s retry), where free-text parsing would
 * fail silently.
 *
 * <p>{@code readiness_band} is deliberately not part of this schema — it's computed
 * server-side from the numeric scores against the fixed thresholds in PROJECT_PLAN.md §3,
 * rather than trusted as a separate model judgement that could disagree with its own scores.
 */
public final class EvaluationTools {

    public static final String SUBMIT_EVALUATION = "submit_evaluation";

    private EvaluationTools() {}

    public static LlmRequest.Tool submitEvaluation() {
        Map<String, Object> scoresSchema = new LinkedHashMap<>();
        scoresSchema.put("type", "object");
        scoresSchema.put("description",
                "Every rubric dimension name, exactly as given in the rubric above, mapped to an "
                        + "integer score 1-5. Every dimension must be present, even ones with thin "
                        + "evidence — use your best judgement rather than omitting one.");

        Map<String, Object> strengthsSchema = new LinkedHashMap<>();
        strengthsSchema.put("type", "array");
        strengthsSchema.put("items", Map.of("type", "string"));
        strengthsSchema.put("description",
                "2-4 specific things the candidate did well, each grounded in something they "
                        + "actually said or wrote — not generic praise.");

        Map<String, Object> gapsSchema = new LinkedHashMap<>();
        gapsSchema.put("type", "array");
        gapsSchema.put("items", Map.of("type", "string"));
        gapsSchema.put("description",
                "1-4 specific things that fell below the SDE-2 bar, each grounded in evidence. "
                        + "An empty list is fine if the round genuinely had none.");

        Map<String, Object> narrativeSchema = new LinkedHashMap<>();
        narrativeSchema.put("type", "string");
        narrativeSchema.put("description",
                "A 2-4 sentence narrative summary in markdown, written for the candidate to read "
                        + "directly — plain, specific, no hedging filler.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("scores", scoresSchema);
        properties.put("strengths", strengthsSchema);
        properties.put("gaps", gapsSchema);
        properties.put("narrative_md", narrativeSchema);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Collections.unmodifiableMap(properties));
        schema.put("required", List.of("scores", "strengths", "gaps", "narrative_md"));

        return new LlmRequest.Tool(
                SUBMIT_EVALUATION,
                "Submit your completed evaluation of this round. Call this exactly once, after "
                        + "you have weighed the recorded signals and the transcript. This is the only "
                        + "way to record your evaluation — a prose reply instead of this call is "
                        + "discarded and re-requested.",
                Collections.unmodifiableMap(schema));
    }
}
