package com.premd.interviewloop.e2e;

import com.premd.interviewloop.evaluation.EvaluationTools;
import com.premd.interviewloop.interviewer.InterviewerTools;
import com.premd.interviewloop.llm.Capabilities;
import com.premd.interviewloop.llm.LlmEvent;
import com.premd.interviewloop.llm.LlmProvider;
import com.premd.interviewloop.llm.LlmRequest;
import com.premd.interviewloop.llm.ProviderFactory;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic stand-in for an LLM, driven by markers in the candidate's own text so a browser
 * test can choose each turn's behaviour:
 *
 * <ul>
 *   <li>{@code [end]} — reply, then call {@code end_round}</li>
 *   <li>{@code [signal]} — reply, a private {@code record_signal}, then more reply (interleaving)</li>
 *   <li>{@code [advance:PHASE]} — reply and request a phase transition</li>
 *   <li>{@code [tool-only]} — tool call with no words (exercises the silent-turn repair)</li>
 *   <li>{@code [slow]} — stream slowly (~4s) so tests can act mid-stream</li>
 *   <li>{@code [code]} — reply containing a fenced code block</li>
 *   <li>{@code [eval-fail]} anywhere in the transcript — the evaluator errors instead of scoring</li>
 * </ul>
 *
 * Anything else gets a short scripted reply that quotes the start of the candidate's text.
 */
public class ScriptedProviderFactory implements ProviderFactory {

    public static final String ID = "scripted";
    public static final String MODEL = "scripted-v1";

    /** Every rubric dimension in the app; the evaluator scores whichever appear in its prompt. */
    private static final List<String> ALL_DIMENSIONS = List.of(
            "clarification", "approach_optimality", "correctness", "complexity_analysis", "edge_cases",
            "communication", "response_to_pushback", "requirement_extraction", "class_model",
            "solid_adherence", "extensibility", "concurrency_handling", "code_quality",
            "requirements_scoping", "capacity_estimation", "component_design", "trade_off_reasoning",
            "bottleneck_identification", "depth_on_probe", "breadth", "precision_of_language",
            "honesty_at_boundary", "api_fluency", "internals_depth", "concurrency_correctness",
            "framework_trade_offs", "scenario_diagnosis", "specificity", "ownership", "self_awareness",
            "impact_and_result", "communication_structure", "ownership_clarity", "technical_depth",
            "decision_reasoning", "impact_articulation", "consistency_with_resume");

    private static final Pattern ADVANCE = Pattern.compile("\\[advance:([A-Z_]+)]");

    private final LlmProvider provider = new ScriptedProvider();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public LlmProvider create(String apiKey) {
        return provider;
    }

    static final class ScriptedProvider implements LlmProvider {
        @Override public String id() { return ID; }
        @Override public String displayName() { return "Scripted (e2e)"; }
        @Override public Capabilities capabilities() {
            return new Capabilities(true, true, Capabilities.PromptCachingMode.NONE, false);
        }

        @Override
        public Flux<LlmEvent> stream(LlmRequest request) {
            boolean evaluator = request.getTools().stream()
                    .anyMatch(t -> EvaluationTools.SUBMIT_EVALUATION.equals(t.getName()));
            return evaluator ? evaluate(request) : interview(request);
        }

        private Flux<LlmEvent> evaluate(LlmRequest request) {
            String everything = allText(request);
            if (everything.contains("[eval-fail]")) {
                return Flux.just(LlmEvent.error("Scripted evaluator failure (requested by [eval-fail])"));
            }
            String system = request.getSystemMessages().stream().map(LlmRequest.Message::getContent)
                    .reduce("", (a, b) -> a + "\n" + b);
            Map<String, Object> scores = new LinkedHashMap<>();
            for (String dimension : ALL_DIMENSIONS) {
                if (system.contains(dimension)) scores.put(dimension, 3);
            }
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("scores", scores);
            args.put("strengths", List.of("Explained the approach before writing code (scripted)."));
            args.put("gaps", List.of("Did not test an empty input (scripted)."));
            args.put("narrative_md", "Scripted evaluation for browser tests. Not a real assessment.");
            return Flux.just(
                    LlmEvent.toolCall(EvaluationTools.SUBMIT_EVALUATION, "eval-1", args),
                    LlmEvent.usage(new LlmEvent.Usage(40, 20, 0, 0)),
                    LlmEvent.done());
        }

        private Flux<LlmEvent> interview(LlmRequest request) {
            String last = lastCandidateText(request.getConversationMessages());
            boolean toolsWithheld = request.getTools().isEmpty();
            List<LlmEvent> events = new ArrayList<>();

            if (toolsWithheld) {
                // The orchestrator's silent-turn continuation.
                chunk(events, "Sorry — to continue: walk me through your first step.");
            } else if (last.contains("[tool-only]")) {
                events.add(LlmEvent.toolCall(InterviewerTools.SET_HINT_LEVEL, "hint-1",
                        Map.of("level", 1, "rationale", "PRIVATE scripted rationale")));
            } else {
                String quoted = last.replaceAll("\\[[^]]*]", "").trim();
                if (quoted.length() > 40) quoted = quoted.substring(0, 40) + "…";
                chunk(events, "Scripted interviewer: I heard \"" + quoted + "\". ");
                if (last.contains("[signal]")) {
                    events.add(LlmEvent.toolCall(InterviewerTools.RECORD_SIGNAL, "sig-1", Map.of(
                            "dimension", "communication", "score", 4, "confidence", "medium",
                            "evidence", "PRIVATE scripted evidence")));
                }
                Matcher advance = ADVANCE.matcher(last);
                if (advance.find()) {
                    events.add(LlmEvent.toolCall(InterviewerTools.ADVANCE_PHASE, "adv-1",
                            Map.of("target_phase", advance.group(1), "rationale", "PRIVATE scripted rationale")));
                }
                if (last.contains("[code]")) {
                    chunk(events, "Consider this:\n\n```java\nint mid = lo + (hi - lo) / 2;\n```\n\n");
                }
                chunk(events, "What would you check next?");
                if (last.contains("[end]")) {
                    events.add(LlmEvent.toolCall(InterviewerTools.END_ROUND, "end-1",
                            Map.of("reason", "PRIVATE scripted end reason")));
                }
            }
            events.add(LlmEvent.usage(new LlmEvent.Usage(120, 30, 0, 0)));
            events.add(LlmEvent.done());

            Duration pace = last.contains("[slow]") ? Duration.ofMillis(400) : Duration.ofMillis(25);
            return Flux.fromIterable(events).delayElements(pace);
        }

        /**
         * The assembler appends the phase directive and the artifact after the transcript as
         * "[Label]\n…" user messages, so the candidate's words are the last message that is not one.
         */
        private static String lastCandidateText(List<LlmRequest.Message> messages) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                LlmRequest.Message m = messages.get(i);
                String content = m.getContent() == null ? "" : m.getContent();
                if ("user".equals(m.getRole()) && !content.startsWith("[")) return content;
            }
            return "";
        }

        private static void chunk(List<LlmEvent> events, String text) {
            for (String word : text.split("(?<= )")) {
                events.add(LlmEvent.textDelta(word));
            }
        }

        private static String allText(LlmRequest request) {
            StringBuilder sb = new StringBuilder();
            request.getSystemMessages().forEach(m -> sb.append(m.getContent()).append('\n'));
            request.getConversationMessages().forEach(m -> sb.append(m.getContent()).append('\n'));
            return sb.toString();
        }
    }
}
