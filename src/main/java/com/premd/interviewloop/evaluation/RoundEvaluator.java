package com.premd.interviewloop.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.premd.interviewloop.domain.RoundEvaluation;
import com.premd.interviewloop.domain.SessionRound;
import com.premd.interviewloop.domain.Signal;
import com.premd.interviewloop.domain.TranscriptTurn;
import com.premd.interviewloop.domain.enums.ReadinessBand;
import com.premd.interviewloop.domain.repository.RoundEvaluationRepository;
import com.premd.interviewloop.domain.repository.SessionRoundRepository;
import com.premd.interviewloop.domain.repository.SignalRepository;
import com.premd.interviewloop.interviewer.InterviewerModule;
import com.premd.interviewloop.interviewer.ModuleRegistry;
import com.premd.interviewloop.llm.AppSettingsStore;
import com.premd.interviewloop.llm.CostLedger;
import com.premd.interviewloop.llm.LlmEvent;
import com.premd.interviewloop.llm.LlmRequest;
import com.premd.interviewloop.llm.ProviderRegistry;
import com.premd.interviewloop.progress.ReadinessCalculator;
import com.premd.interviewloop.transcript.TranscriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Scores a completed round: aggregates the signals recorded incrementally during the round
 * (PROJECT_PLAN.md §1.3 — "a cheap summarisation of already-collected evidence rather than a
 * second full pass over the transcript"), asks the pinned evaluator to weigh them, and persists
 * the result.
 *
 * <p>Runs after the round is already {@code COMPLETED} — never inside the transaction that
 * transitions it there, same reason streaming a turn never runs inside one. A failure here must
 * never undo or block round completion; callers treat {@link EvaluationFailedException} as
 * "no evaluation yet", not as a reason to fail the request that triggered it.
 */
@Service
public class RoundEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RoundEvaluator.class);
    private static final Duration EVAL_TIMEOUT = Duration.ofSeconds(60);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * H5 (docs/TASKS.md, PROJECT_PLAN.md §3): LLM-as-judge scoring is known to drift toward
     * the middle or toward generosity without concrete anchors. Deliberately generic and
     * module-agnostic (option (a) from the H5 card) rather than per-module — this keeps
     * {@code evaluation} independent of module content, matching the package boundary in
     * AGENTS.md's architecture map, and costs nothing to maintain as modules are added.
     *
     * <p>These are illustrative patterns, not a real transcript — the closing line of the
     * block tells the evaluator that explicitly so it cannot mistake them for this round's
     * own evidence. Inserted after {@code module.rubric()} in {@link #buildRequest}, its own
     * clearly-labelled block, never mixed into the rubric text itself — so no module's
     * {@code rubricVersion()} needs bumping for this.
     */
    private static final String CALIBRATION_ANCHORS = """
            CALIBRATION EXAMPLES (not this round's candidate — illustrative only):

            These show the difference between a 1-2 (below bar) and a 4-5 (above bar)
            response, for ANY dimension in the rubric above. Apply the same standard to the
            real evidence below, whatever that round's actual dimensions and question were.

            BELOW THE BAR (1-2) looks like this: the candidate asserts a conclusion with no
            mechanism behind it, and gives up the first time they're pushed. Example: asked
            how their design stays correct under concurrent access, the candidate says "it
            would just work." Pushed once — "what if two threads update it at the same
            time?" — they reply "I guess we'd add a lock somewhere," naming no specific lock,
            no specific data it protects, and no reason it would actually prevent the race.
            Confident-sounding language does not raise this score; the absence of a named,
            checkable mechanism is what caps it low, whether the candidate sounds unsure or
            sounds completely certain while saying nothing concrete.

            ABOVE THE BAR (4-5) looks like this: the candidate names an actual mechanism and
            engages with a push-back by reasoning about the new constraint, not by repeating
            themselves or caving. Example: asked the same concurrency question, the candidate
            says "I'd lock per bucket in the hash map rather than one global lock, so updates
            to different keys don't block each other — costs more memory for the lock array,
            but concurrent writes to different keys proceed in parallel." Pushed further —
            "what if two threads update the SAME key at once?" — they correctly identify that
            the per-bucket lock already covers that case and explain why, rather than
            introducing a new, unrelated fix or simply agreeing there might be a problem.

            The generalizable signal, across every dimension in this rubric: specific beats
            vague, a named mechanism beats a gesture at one, and engaging with a push-back
            with adapted reasoning beats repeating the same answer or immediately conceding.
            Score what was actually demonstrated in the real evidence below against that
            standard — these two examples are calibration, not this round's transcript.
            """;

    private final SessionRoundRepository roundRepo;
    private final SignalRepository signalRepo;
    private final RoundEvaluationRepository evaluationRepo;
    private final TranscriptService transcriptService;
    private final ModuleRegistry moduleRegistry;
    private final ProviderRegistry providerRegistry;
    private final AppSettingsStore settingsStore;
    private final CostLedger costLedger;
    private final ReadinessCalculator readinessCalculator;
    private final SessionReporter sessionReporter;

    public RoundEvaluator(SessionRoundRepository roundRepo, SignalRepository signalRepo,
                          RoundEvaluationRepository evaluationRepo, TranscriptService transcriptService,
                          ModuleRegistry moduleRegistry, ProviderRegistry providerRegistry,
                          AppSettingsStore settingsStore, CostLedger costLedger,
                          ReadinessCalculator readinessCalculator, SessionReporter sessionReporter) {
        this.roundRepo = roundRepo;
        this.signalRepo = signalRepo;
        this.evaluationRepo = evaluationRepo;
        this.transcriptService = transcriptService;
        this.moduleRegistry = moduleRegistry;
        this.providerRegistry = providerRegistry;
        this.settingsStore = settingsStore;
        this.costLedger = costLedger;
        this.readinessCalculator = readinessCalculator;
        this.sessionReporter = sessionReporter;
    }

    public RoundEvaluation evaluate(Long roundId) {
        SessionRound round = roundRepo.findByIdWithSession(roundId)
                .orElseThrow(() -> new NoSuchElementException("Round not found: " + roundId));
        InterviewerModule module = moduleRegistry.require(round.getModuleType());
        ProviderRegistry.Resolved evaluator = providerRegistry.resolveEvaluator();
        long startedAt = System.currentTimeMillis();

        LlmRequest request = buildRequest(round, module, evaluator.model(), false);
        CallOutcome outcome = callAndParse(evaluator, request);

        if (!isUsable(outcome.result())) {
            log.warn("Round {}: evaluator returned no usable submit_evaluation call, retrying once", roundId);
            LlmRequest retry = buildRequest(round, module, evaluator.model(), true);
            CallOutcome retryOutcome = callAndParse(evaluator, retry);
            outcome = new CallOutcome(retryOutcome.result(), sumUsage(outcome.usage(), retryOutcome.usage()));
        }

        int latencyMs = (int) (System.currentTimeMillis() - startedAt);
        if (outcome.usage() != null) {
            costLedger.record(evaluator.provider().id(), evaluator.model(), "evaluator",
                    outcome.usage(), latencyMs, round, null);
        }

        if (!isUsable(outcome.result())) {
            throw new EvaluationFailedException(
                    "The evaluator did not return a usable evaluation for round " + roundId + " after a retry");
        }

        return persist(round, module, evaluator, outcome.result());
    }

    private boolean isUsable(EvaluationResult result) {
        return result != null && !result.scores().isEmpty();
    }

    private LlmRequest buildRequest(SessionRound round, InterviewerModule module, String model, boolean nudge) {
        List<Signal> signals = signalRepo.findByRoundIdOrderByIdAsc(round.getId());
        List<TranscriptTurn> transcript = transcriptService.getTranscript(round.getId());

        String system = """
                You are scoring a completed technical interview round, after the fact — you did not
                conduct it. Signals were already recorded incrementally, during the round, by the
                interviewer model that did conduct it; treat them as your primary evidence. The
                transcript below is there to fill gaps and ground your narrative, not to re-derive
                everything from scratch.

                Calibrate to an SDE-2 bar: roughly two years of professional experience. Do not
                inflate scores because the candidate was pleasant or confident — score what they
                actually demonstrated, and quote or paraphrase real evidence for every strength and
                gap you name.

                """ + module.rubric() + "\n" + CALIBRATION_ANCHORS;

        StringBuilder evidence = new StringBuilder("RECORDED SIGNALS (already collected during the round):\n");
        if (signals.isEmpty()) {
            evidence.append("(none were recorded — score from the transcript alone.)\n");
        } else {
            for (Signal s : signals) {
                evidence.append("- ").append(s.getRubricDimension())
                        .append(": score=").append(s.getScore())
                        .append(" confidence=").append(s.getConfidence())
                        .append(" evidence=\"").append(s.getEvidence()).append("\"\n");
            }
        }

        evidence.append("\nFULL TRANSCRIPT:\n");
        for (TranscriptTurn turn : transcript) {
            evidence.append(turn.getRole()).append(": ").append(turn.getContent()).append("\n\n");
        }

        List<LlmRequest.Message> conversation = new ArrayList<>();
        conversation.add(new LlmRequest.Message("user", evidence.toString(), false));
        if (nudge) {
            conversation.add(new LlmRequest.Message("user",
                    "(Your previous attempt did not call submit_evaluation. You must call it now — "
                            + "a prose reply is not a valid response here.)", false));
        }

        return new LlmRequest()
                .model(model)
                .tools(List.of(EvaluationTools.submitEvaluation()))
                .systemMessages(List.of(new LlmRequest.Message("system", system, false)))
                .conversationMessages(conversation)
                .temperature(0.3)
                .maxTokens(2048);
    }

    private record CallOutcome(EvaluationResult result, LlmEvent.Usage usage) {}

    private CallOutcome callAndParse(ProviderRegistry.Resolved evaluator, LlmRequest request) {
        LlmEvent.Usage usage = null;
        Map<String, Object> args = null;
        try {
            for (LlmEvent event : evaluator.provider().stream(request).timeout(EVAL_TIMEOUT).toIterable()) {
                switch (event.getType()) {
                    case TOOL_CALL -> {
                        if (args == null && EvaluationTools.SUBMIT_EVALUATION.equals(event.getToolCall().name())) {
                            args = event.getToolCall().arguments();
                        }
                    }
                    case USAGE -> usage = event.getUsage();
                    case ERROR -> log.warn("Evaluator call errored: {}", event.getErrorMessage());
                    default -> { /* prose from the evaluator is not candidate-facing; discard */ }
                }
            }
        } catch (Exception e) {
            log.warn("Evaluator call failed: {}", e.getMessage());
            return new CallOutcome(null, usage);
        }
        return new CallOutcome(args == null ? null : parseResult(args), usage);
    }

    private EvaluationResult parseResult(Map<String, Object> args) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        if (args.get("scores") instanceof Map<?, ?> raw) {
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                Integer clamped = toClampedScore(entry.getValue());
                if (clamped != null) {
                    scores.put(String.valueOf(entry.getKey()), clamped);
                }
            }
        }
        return new EvaluationResult(
                scores,
                toStringList(args.get("strengths")),
                toStringList(args.get("gaps")),
                args.get("narrative_md") == null ? "" : String.valueOf(args.get("narrative_md")));
    }

    private Integer toClampedScore(Object value) {
        try {
            int i = value instanceof Number n ? n.intValue()
                    : (int) Math.round(Double.parseDouble(String.valueOf(value).trim()));
            return Math.max(1, Math.min(5, i));
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private LlmEvent.Usage sumUsage(LlmEvent.Usage a, LlmEvent.Usage b) {
        if (a == null) return b;
        if (b == null) return a;
        return new LlmEvent.Usage(
                a.inputTokens() + b.inputTokens(),
                a.outputTokens() + b.outputTokens(),
                a.cacheReadTokens() + b.cacheReadTokens(),
                a.cacheWriteTokens() + b.cacheWriteTokens());
    }

    private RoundEvaluation persist(SessionRound round, InterviewerModule module,
                                    ProviderRegistry.Resolved evaluator, EvaluationResult result) {
        double mean = result.scores().values().stream().mapToInt(Integer::intValue).average().orElse(0);
        ReadinessBand band = ReadinessBand.fromScore(mean);

        RoundEvaluation evaluation = new RoundEvaluation(round);
        evaluation.setRubricVersion(module.rubricVersion());
        evaluation.setEvaluatorProvider(evaluator.provider().id());
        evaluation.setEvaluatorModel(evaluator.model());
        evaluation.setComparabilityEpoch(settingsStore.comparabilityEpoch());
        evaluation.setScores(toJson(result.scores()));
        evaluation.setStrengths(toJson(result.strengths()));
        evaluation.setGaps(toJson(result.gaps()));
        evaluation.setReadinessBand(band.wireValue());
        evaluation.setNarrativeMd(result.narrativeMd());

        RoundEvaluation saved = evaluationRepo.save(evaluation);
        log.info("Round {} evaluated: band={} mean={}", round.getId(), band.wireValue(), String.format("%.2f", mean));

        // Best-effort: record a readiness snapshot for trend tracking.
        try {
            String companyProfileId = round.getSession().getCompanyProfileId();
            readinessCalculator.recordSnapshot(
                    round.getModuleType().getValue(), companyProfileId, mean, evaluation.getComparabilityEpoch());
        } catch (Exception e) {
            log.warn("Round {}: readiness snapshot failed — {}", round.getId(), e.getMessage());
        }

        // The final evaluation is now persisted. If it completed the session, this is the
        // first safe moment to aggregate every round into a report.
        try {
            sessionReporter.reportIfSessionCompleted(round.getSession().getId());
        } catch (Exception e) {
            log.warn("Round {}: session report generation failed — {}", round.getId(), e.getMessage());
        }

        return saved;
    }

    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise evaluation field", e);
        }
    }
}
