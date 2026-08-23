package com.premd.interviewloop.session;

import com.premd.interviewloop.domain.SessionRound;
import com.premd.interviewloop.domain.Signal;
import com.premd.interviewloop.domain.TranscriptTurn;
import com.premd.interviewloop.domain.enums.RoundStatus;
import com.premd.interviewloop.domain.enums.TurnRole;
import com.premd.interviewloop.domain.repository.SessionRoundRepository;
import com.premd.interviewloop.domain.repository.SignalRepository;
import com.premd.interviewloop.interviewer.ControlCall;
import com.premd.interviewloop.interviewer.InterviewerModule;
import com.premd.interviewloop.interviewer.ModuleRegistry;
import com.premd.interviewloop.interviewer.QuestionSelection;
import com.premd.interviewloop.interviewer.RoundContext;
import com.premd.interviewloop.llm.CostLedger;
import com.premd.interviewloop.llm.LlmEvent;
import com.premd.interviewloop.llm.LlmRequest;
import com.premd.interviewloop.llm.PromptAssembler;
import com.premd.interviewloop.llm.ProviderRegistry;
import com.premd.interviewloop.transcript.TranscriptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs one candidate turn end to end.
 *
 * <p>This is the loop from PROJECT_PLAN.md §1.3, and the reason the interviewer behaves like a
 * state machine rather than a chatbot:
 *
 * <ol>
 *   <li>persist what the candidate said and the artifact they had on screen</li>
 *   <li>assemble the prompt stable-prefix-first, with the phase directive after the breakpoint</li>
 *   <li>stream the response, forwarding tokens as they arrive</li>
 *   <li>persist the interviewer's turn</li>
 *   <li><b>apply the model's control calls — or refuse them</b></li>
 *   <li>record token usage and cost</li>
 * </ol>
 *
 * <p>Step 5 is the load-bearing one. The model asks to advance a phase; the state machine decides.
 * A model that would happily skip complexity analysis because the candidate sounded confident is
 * exactly what this prevents.
 *
 * <p>Streaming deliberately happens outside any transaction — a slow provider must not hold a
 * database connection open for the length of a model response.
 */
@Service
public class TurnOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(TurnOrchestrator.class);

    /** A turn that has produced nothing for this long is treated as a failed turn. */
    private static final Duration TURN_TIMEOUT = Duration.ofSeconds(120);

    private final SessionRoundRepository roundRepo;
    private final SignalRepository signalRepo;
    private final SessionManager sessionManager;
    private final TranscriptService transcriptService;
    private final RoundContextFactory contextFactory;
    private final ModuleRegistry moduleRegistry;
    private final ProviderRegistry providerRegistry;
    private final PromptAssembler promptAssembler;
    private final CostLedger costLedger;

    /**
     * Hint level per round. Not persisted: it is a within-round escalation counter, and a round
     * that has been interrupted and resumed should start the candidate back at no help rather
     * than silently continuing to hand out hints.
     */
    private final Map<Long, Integer> hintLevels = new ConcurrentHashMap<>();

    public TurnOrchestrator(SessionRoundRepository roundRepo,
                            SignalRepository signalRepo,
                            SessionManager sessionManager,
                            TranscriptService transcriptService,
                            RoundContextFactory contextFactory,
                            ModuleRegistry moduleRegistry,
                            ProviderRegistry providerRegistry,
                            PromptAssembler promptAssembler,
                            CostLedger costLedger) {
        this.roundRepo = roundRepo;
        this.signalRepo = signalRepo;
        this.sessionManager = sessionManager;
        this.transcriptService = transcriptService;
        this.contextFactory = contextFactory;
        this.moduleRegistry = moduleRegistry;
        this.providerRegistry = providerRegistry;
        this.promptAssembler = promptAssembler;
        this.costLedger = costLedger;
    }

    /** Whether a round can actually be conducted, or is waiting on a module that is not built yet. */
    public boolean canConduct(Long roundId) {
        return roundRepo.findById(roundId)
                .map(r -> moduleRegistry.isImplemented(r.getModuleType()))
                .orElse(false);
    }

    /**
     * Open a round: choose the question, pin the provider, and send the opening brief.
     * The brief is persisted as the round's first transcript turn so replay is complete.
     *
     * <p>Idempotent on {@code questionSlug}, not on round status. The web client starts a round
     * over REST first (moving it PENDING → IN_PROGRESS) and then sends this over the socket
     * purely to bind the connection and receive the opening brief — so by the time this runs,
     * the round is routinely already IN_PROGRESS, and that is not a re-entrant call to reject.
     * A question already being pinned is what actually means "this round has begun"; reject
     * nothing but a truly repeated begin (a stale reconnect resending {@code start_round} after
     * the question was already chosen), where the right behaviour is a silent no-op rather than
     * re-picking a question or duplicating the opening transcript turn.
     */
    public void beginRound(Long roundId, TurnSink sink) {
        SessionRound pending = roundRepo.findById(roundId)
                .orElseThrow(() -> new NoSuchElementException("Round not found: " + roundId));

        // Check the module exists before touching round state — otherwise a missing module
        // would leave a round stuck IN_PROGRESS with no way to conduct it.
        InterviewerModule module = moduleRegistry.require(pending.getModuleType());

        if (pending.getQuestionSlug() != null) {
            sink.turnComplete(roundId);
            return;
        }

        // Only run the PENDING -> IN_PROGRESS transition if REST /start hasn't already done it —
        // the client is written to fall back to this socket call driving the round alone if that
        // REST call failed, so it must still work when it hasn't run at all.
        if (pending.getStatus() == RoundStatus.PENDING) {
            sessionManager.startRound(roundId);
        }
        hintLevels.remove(roundId);

        RoundContext ctx = contextFactory.build(roundId, 0);

        QuestionSelection question = module.selectQuestion(ctx);
        ProviderRegistry.Resolved interviewer = providerRegistry.resolveInterviewer();
        pinRound(roundId, question, interviewer);

        // Rebuild so the brief sees the question that was just pinned.
        RoundContext briefed = contextFactory.build(roundId, 0);
        String brief = module.openingBrief(briefed);

        transcriptService.appendTurn(roundId, TurnRole.INTERVIEWER, brief);
        sink.textDelta(brief);
        sink.turnComplete(roundId);
    }

    /**
     * Not {@code @Transactional}: this is called from within the same bean, so the proxy would be
     * bypassed and the annotation would be a lie. The single {@code save} is transactional on its
     * own, and nothing here touches a lazy association.
     */
    private void pinRound(Long roundId, QuestionSelection question,
                          ProviderRegistry.Resolved interviewer) {
        SessionRound round = roundRepo.findById(roundId)
                .orElseThrow(() -> new NoSuchElementException("Round not found: " + roundId));
        round.setQuestionSlug(question.slug());
        round.setQuestionContentHash(question.contentHash());
        round.setInterviewerProvider(interviewer.provider().id());
        round.setInterviewerModel(interviewer.model());
        roundRepo.save(round);
    }

    /**
     * Handle one candidate turn.
     *
     * @param artifact the candidate's current editor buffer or diagram graph, or null
     */
    public void handleCandidateTurn(Long roundId, String text, String artifact, TurnSink sink) {
        SessionRound round = roundRepo.findById(roundId)
                .orElseThrow(() -> new NoSuchElementException("Round not found: " + roundId));

        if (round.getStatus() != RoundStatus.IN_PROGRESS) {
            sink.error("Round " + roundId + " is " + round.getStatus() + ", not in progress");
            return;
        }

        InterviewerModule module = moduleRegistry.require(round.getModuleType());

        // 1. Persist the candidate's turn and whatever was on their screen.
        TranscriptTurn candidateTurn = transcriptService.appendTurn(roundId, TurnRole.CANDIDATE, text);
        if (artifact != null && !artifact.isBlank()) {
            transcriptService.saveArtifact(roundId, candidateTurn.getId(),
                    module.artifactKind(), module.artifactLanguage(), artifact);
        }

        // 2. Assemble stable-prefix-first. The phase directive and the artifact are volatile and
        //    must land after the cache breakpoint — putting either in the system block would
        //    invalidate the cached prefix on every turn.
        int hintLevel = hintLevels.getOrDefault(roundId, 0);
        RoundContext ctx = contextFactory.build(roundId, hintLevel);
        ProviderRegistry.Resolved interviewer = resolveFor(round);

        LlmRequest request = promptAssembler.assemble(
                interviewer.model(),
                module.tools(),
                module.rubric(),
                module.persona(ctx),
                module.problemBlock(ctx),
                toMessages(transcriptService.getTranscript(roundId)),
                module.phaseDirective(ctx),
                artifact,
                module.maxResponseTokens());

        // 3. Stream, outside any transaction.
        StringBuilder prose = new StringBuilder();
        List<ControlCall> controlCalls = new ArrayList<>();
        LlmEvent.Usage usage = null;
        long startedAt = System.currentTimeMillis();

        try {
            for (LlmEvent event : interviewer.provider().stream(request)
                    .timeout(TURN_TIMEOUT)
                    .toIterable()) {
                switch (event.getType()) {
                    case TEXT_DELTA -> {
                        prose.append(event.getTextDelta());
                        sink.textDelta(event.getTextDelta());
                    }
                    case TOOL_CALL -> {
                        LlmEvent.ToolCall call = event.getToolCall();
                        sink.toolCall(call.name(), call.id(), call.arguments());
                        controlCalls.add(ControlCall.parse(call.name(), call.arguments()));
                    }
                    case USAGE -> usage = event.getUsage();
                    case ERROR -> sink.error(event.getErrorMessage());
                    case DONE -> { /* loop ends */ }
                }
            }
        } catch (Exception e) {
            log.error("Turn failed for round {}", roundId, e);
            sink.error("The interviewer could not respond: " + e.getMessage());
            return;
        }

        // 3b. Standard function-calling protocol trains a model to pause after emitting a tool
        // call and wait for a function *response* before continuing. This app's control tools
        // never send one back — they're applied silently, server-side — so a model that decided
        // to call one is doing exactly what it was trained to do by going silent afterward. No
        // amount of prompt wording fixes that reliably; a candidate must never see literal
        // silence, so retry once, tools withheld, asking specifically for the words that didn't
        // arrive. Bounded to one retry and only fires on this genuinely degenerate case.
        if (prose.isEmpty() && !controlCalls.isEmpty()) {
            usage = appendSilentTurnContinuation(request, controlCalls, interviewer, prose, sink, usage);
        }

        int latencyMs = (int) (System.currentTimeMillis() - startedAt);

        // 4. Persist the interviewer's turn.
        TranscriptTurn interviewerTurn = null;
        if (prose.length() > 0) {
            interviewerTurn = transcriptService.appendTurn(
                    roundId, TurnRole.INTERVIEWER, prose.toString(), latencyMs);
        }

        // 5. Apply control calls — the backend decides, not the model.
        applyControlCalls(roundId, controlCalls, interviewerTurn, sink);

        // 6. Record what the turn cost.
        if (usage != null) {
            var call = costLedger.record(interviewer.provider().id(), interviewer.model(),
                    "interviewer", usage, latencyMs, round, interviewerTurn);
            sink.usage(usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(),
                    call.getCostEstimateUsd() == null ? 0.0 : call.getCostEstimateUsd());
        }

        sink.turnComplete(roundId);
    }

    private void applyControlCalls(Long roundId, List<ControlCall> calls,
                                   TranscriptTurn turn, TurnSink sink) {
        for (ControlCall call : calls) {
            try {
                if (call instanceof ControlCall.RecordSignal s) {
                    persistSignal(roundId, s, turn);

                } else if (call instanceof ControlCall.AdvancePhase a) {
                    SessionRound updated = sessionManager.advancePhase(roundId, a.targetPhase());
                    sink.phaseAdvanced(updated.getPhase());

                } else if (call instanceof ControlCall.SetHintLevel h) {
                    // Hints only ever escalate. A model that lowers the hint level mid-round
                    // would be rewriting how much help it had already given.
                    hintLevels.merge(roundId, h.level(), Math::max);

                } else if (call instanceof ControlCall.EndRound e) {
                    sessionManager.completeRound(roundId);
                    hintLevels.remove(roundId);
                    sink.roundCompleted(roundId);

                } else if (call instanceof ControlCall.Malformed m) {
                    log.warn("Round {}: ignoring malformed control call {} ({})",
                            roundId, m.toolName(), m.problem());
                }
            } catch (IllegalStateException e) {
                // A refused transition is normal operation, not a failure: the state machine just
                // stopped the model from skipping a phase. The round continues.
                log.info("Round {}: refused control call {} — {}",
                        roundId, call.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /** Same self-invocation caveat as {@link #pinRound} — the lone save is its own transaction. */
    private void persistSignal(Long roundId, ControlCall.RecordSignal s, TranscriptTurn turn) {
        SessionRound round = roundRepo.findById(roundId)
                .orElseThrow(() -> new NoSuchElementException("Round not found: " + roundId));
        Signal signal = new Signal(round, s.dimension(), s.score());
        signal.setConfidence(s.confidence());
        signal.setEvidence(s.evidence());
        signal.setTurn(turn);
        signalRepo.save(signal);
    }

    private ProviderRegistry.Resolved resolveFor(SessionRound round) {
        // A round stays on the provider it was opened with, so a mid-round provider switch in
        // settings cannot change the interviewer the candidate is talking to.
        if (round.getInterviewerProvider() != null && round.getInterviewerModel() != null) {
            return providerRegistry.getProvider(round.getInterviewerProvider())
                    .map(p -> new ProviderRegistry.Resolved(p, round.getInterviewerModel()))
                    .orElseGet(providerRegistry::resolveInterviewer);
        }
        return providerRegistry.resolveInterviewer();
    }

    private List<LlmRequest.Message> toMessages(List<TranscriptTurn> turns) {
        List<LlmRequest.Message> messages = new ArrayList<>(turns.size());
        for (TranscriptTurn turn : turns) {
            String role = switch (turn.getRole()) {
                case CANDIDATE -> "user";
                case INTERVIEWER -> "assistant";
                case SYSTEM -> "system";
            };
            messages.add(new LlmRequest.Message(role, turn.getContent(), false));
        }
        return messages;
    }

    /**
     * One bounded retry for a turn that made tool calls but said nothing. Withholds tools
     * entirely, so the model has nothing to do but produce the words it skipped — this cannot
     * recurse into another silent turn. Streams straight into {@code prose} and {@code sink} on
     * success; a failure here is logged and swallowed; the candidate keeps whatever silence there
     * was rather than losing the tool calls that already succeeded.
     *
     * @return usage summed across both calls, so the ledger reflects the turn's true cost
     */
    private LlmEvent.Usage appendSilentTurnContinuation(LlmRequest original, List<ControlCall> calls,
                                                         ProviderRegistry.Resolved interviewer,
                                                         StringBuilder prose, TurnSink sink,
                                                         LlmEvent.Usage firstUsage) {
        List<LlmRequest.Message> messages = new ArrayList<>(original.getConversationMessages());
        messages.add(new LlmRequest.Message("assistant", summarizeSilentCalls(calls), false));
        messages.add(new LlmRequest.Message("user",
                "(Nothing you said reached the candidate in your last turn — from their side, "
                        + "you just went silent. Reply now, in a sentence or two, addressing "
                        + "what they said or asked.)", false));

        LlmRequest continuation = new LlmRequest()
                .model(original.getModel())
                .systemMessages(original.getSystemMessages())
                .conversationMessages(messages)
                .temperature(original.getTemperature())
                .maxTokens(original.getMaxTokens())
                .tools(List.of());

        LlmEvent.Usage continuationUsage = null;
        try {
            for (LlmEvent event : interviewer.provider().stream(continuation)
                    .timeout(TURN_TIMEOUT)
                    .toIterable()) {
                if (event.getType() == LlmEvent.Type.TEXT_DELTA) {
                    prose.append(event.getTextDelta());
                    sink.textDelta(event.getTextDelta());
                } else if (event.getType() == LlmEvent.Type.USAGE) {
                    continuationUsage = event.getUsage();
                }
            }
        } catch (Exception e) {
            log.warn("Silent-turn continuation failed, leaving the turn as-is: {}", e.getMessage());
        }

        if (continuationUsage == null) {
            return firstUsage;
        }
        if (firstUsage == null) {
            return continuationUsage;
        }
        return new LlmEvent.Usage(
                firstUsage.inputTokens() + continuationUsage.inputTokens(),
                firstUsage.outputTokens() + continuationUsage.outputTokens(),
                firstUsage.cacheReadTokens() + continuationUsage.cacheReadTokens(),
                firstUsage.cacheWriteTokens() + continuationUsage.cacheWriteTokens());
    }

    private String summarizeSilentCalls(List<ControlCall> calls) {
        StringBuilder sb = new StringBuilder();
        for (ControlCall call : calls) {
            if (!sb.isEmpty()) sb.append(" ");
            if (call instanceof ControlCall.RecordSignal s) {
                sb.append("[Noted ").append(s.dimension()).append(".]");
            } else if (call instanceof ControlCall.AdvancePhase a) {
                sb.append("[Requested moving to ").append(a.targetPhase()).append(".]");
            } else if (call instanceof ControlCall.SetHintLevel h) {
                sb.append("[Raised hint level to ").append(h.level()).append(".]");
            } else if (call instanceof ControlCall.EndRound e) {
                sb.append("[Requested ending the round.]");
            }
        }
        return sb.isEmpty() ? "[Sent no reply.]" : sb.toString();
    }
}
