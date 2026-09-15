package com.premd.interviewloop.session;

import com.premd.interviewloop.domain.InterviewSession;
import com.premd.interviewloop.domain.LlmCall;
import com.premd.interviewloop.domain.ReadinessSnapshot;
import com.premd.interviewloop.domain.RoundEvaluation;
import com.premd.interviewloop.domain.SessionRound;
import com.premd.interviewloop.domain.TranscriptTurn;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.domain.enums.RoundPhase;
import com.premd.interviewloop.domain.enums.RoundStatus;
import com.premd.interviewloop.domain.enums.SessionMode;
import com.premd.interviewloop.domain.enums.TurnRole;
import com.premd.interviewloop.domain.repository.LlmCallRepository;
import com.premd.interviewloop.domain.repository.ReadinessSnapshotRepository;
import com.premd.interviewloop.domain.repository.SessionReportRepository;
import com.premd.interviewloop.domain.repository.SessionRoundRepository;
import com.premd.interviewloop.evaluation.EvaluationTools;
import com.premd.interviewloop.evaluation.RoundEvaluator;
import com.premd.interviewloop.interviewer.InterviewerTools;
import com.premd.interviewloop.interviewer.ModuleRegistry;
import com.premd.interviewloop.interviewer.RoundContext;
import com.premd.interviewloop.llm.*;
import com.premd.interviewloop.testsupport.ScriptedProviderSupport.ScriptedLlmProvider;
import com.premd.interviewloop.testsupport.ScriptedProviderSupport.ScriptedProviderFactory;
import com.premd.interviewloop.testsupport.ScriptedProviderSupport.TestProviderConfig;
import com.premd.interviewloop.transcript.TranscriptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.util.*;

import static com.premd.interviewloop.testsupport.ScriptedProviderSupport.MOCK_MODEL_ID;
import static com.premd.interviewloop.testsupport.ScriptedProviderSupport.MOCK_PROVIDER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test for the turn loop (Task T3 in docs/TASKS.md).
 *
 * <p>Exercises TurnOrchestrator end-to-end against a mock in-memory LlmProvider (zero network calls,
 * zero API quota burned):
 * <ol>
 *   <li>Happy path: candidate turn → prompt assembly → mock streaming → control call applied
 *       (phase advanced) → transcript persisted → cost ledger written</li>
 *   <li>Silent-turn safety net (AGENTS.md Invariant 4): model returns tool call with zero text →
 *       orchestrator fires continuation retry → words arrive and are persisted</li>
 * </ol>
 */
@SpringBootTest
@Import(TestProviderConfig.class)
class TurnOrchestratorIntegrationTest {

    @Autowired
    private TurnOrchestrator turnOrchestrator;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private SessionRoundRepository roundRepo;

    @Autowired
    private LlmCallRepository llmCallRepo;

    @Autowired
    private TranscriptService transcriptService;

    @Autowired
    private RoundEvaluator roundEvaluator;

    @Autowired
    private RoundContextFactory contextFactory;

    @Autowired
    private ModuleRegistry moduleRegistry;

    @Autowired
    private SessionReportRepository sessionReportRepo;

    @Autowired
    private ReadinessSnapshotRepository readinessSnapshotRepo;

    @Autowired
    private ProviderKeyStore keyStore;

    @Autowired
    private AppSettingsStore settingsStore;

    @Autowired
    private ScriptedProviderFactory providerFactory;

    private ScriptedLlmProvider mockProvider;

    @BeforeEach
    void setUp() {
        mockProvider = providerFactory.getProvider();
        mockProvider.reset();

        keyStore.putUiKey(MOCK_PROVIDER_ID, "fake-test-key");
        settingsStore.setInterviewer(MOCK_PROVIDER_ID, MOCK_MODEL_ID);
    }

    @Test
    void generalPracticeSession_hasNoCompanyProfileButStillBuildsRoundContext() {
        InterviewSession session = sessionManager.createSingleModuleSession(
                GeneralPractice.ID, ModuleType.DSA, "medium", MOCK_PROVIDER_ID, MOCK_MODEL_ID);
        SessionRound round = session.getRounds().get(0);

        RoundContext context = contextFactory.build(round.getId(), 0);

        assertThat(session.getProfileContentHash()).isEqualTo(GeneralPractice.CONTENT_VERSION);
        assertThat(context.companyProfileId()).isEqualTo(GeneralPractice.ID);
        assertThat(context.companyDisplayName()).isNull();
        assertThat(context.targetRoleTitle()).isNull();
        assertThatThrownBy(() -> sessionManager.createSession(
                GeneralPractice.ID, SessionMode.FULL_LOOP, MOCK_PROVIDER_ID, MOCK_MODEL_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("single module");
    }

    @Test
    void testHappyPathCandidateTurn_advancesPhaseAndPersistsLedger() {
        // 1. Create a session and start a DSA round
        InterviewSession session = sessionManager.createSingleModuleSession(
                "google", ModuleType.DSA, "medium", MOCK_PROVIDER_ID, MOCK_MODEL_ID);
        Long roundId = session.getRounds().get(0).getId();

        // 2. Begin the round (opening brief)
        turnOrchestrator.beginRound(roundId, TurnSink.noop());

        SessionRound roundAfterBegin = roundRepo.findById(roundId).orElseThrow();
        assertThat(roundAfterBegin.getPhase()).isEqualTo(RoundPhase.BRIEFING);
        assertThat(roundAfterBegin.getQuestionSlug()).isNotNull();

        // 3. Script the mock LLM response for the candidate turn:
        //    Emits prose, tool call to advance phase to CLARIFYING, usage stats, and DONE.
        mockProvider.enqueueResponse(List.of(
                LlmEvent.textDelta("That is a good starting point. "),
                LlmEvent.textDelta("What constraints do you think apply?"),
                LlmEvent.toolCall(InterviewerTools.ADVANCE_PHASE, "call_adv_1",
                        Map.of("target_phase", "CLARIFYING", "rationale", "Candidate acknowledged problem")),
                LlmEvent.usage(new LlmEvent.Usage(150, 45, 0, 0)),
                LlmEvent.done()
        ));

        // 4. Handle candidate turn
        turnOrchestrator.handleCandidateTurn(roundId, "Should I consider negative numbers?", null, TurnSink.noop());

        // 5. Assertions
        // (a) Turns persisted in transcript
        List<TranscriptTurn> turns = transcriptService.getTranscript(roundId);
        // Turn 1: opening brief (INTERVIEWER)
        // Turn 2: candidate message (CANDIDATE)
        // Turn 3: interviewer response (INTERVIEWER)
        assertThat(turns).hasSizeGreaterThanOrEqualTo(3);

        TranscriptTurn candidateTurn = turns.stream()
                .filter(t -> t.getRole() == TurnRole.CANDIDATE)
                .findFirst().orElseThrow();
        assertThat(candidateTurn.getContent()).isEqualTo("Should I consider negative numbers?");

        TranscriptTurn interviewerTurn = turns.get(turns.size() - 1);
        assertThat(interviewerTurn.getRole()).isEqualTo(TurnRole.INTERVIEWER);
        assertThat(interviewerTurn.getContent()).contains("That is a good starting point. What constraints do you think apply?");

        // (b) Round phase advanced from BRIEFING to CLARIFYING
        SessionRound updatedRound = roundRepo.findById(roundId).orElseThrow();
        assertThat(updatedRound.getPhase()).isEqualTo(RoundPhase.CLARIFYING);

        // (c) llm_call row written with token counts and non-null cost estimate
        List<LlmCall> calls = llmCallRepo.findByRoundIdOrderByCreatedAtAsc(roundId);
        assertThat(calls).isNotEmpty();
        LlmCall lastCall = calls.get(calls.size() - 1);
        assertThat(lastCall.getProvider()).isEqualTo(MOCK_PROVIDER_ID);
        assertThat(lastCall.getModel()).isEqualTo(MOCK_MODEL_ID);
        assertThat(lastCall.getInputTokens()).isEqualTo(150);
        assertThat(lastCall.getOutputTokens()).isEqualTo(45);
        assertThat(lastCall.getCostEstimateUsd()).isNotNull();
    }

    @Test
    void testSilentTurnSafetyNet_retriesAndPersistsWords() {
        // 1. Create a session and start a round
        InterviewSession session = sessionManager.createSingleModuleSession(
                "google", ModuleType.DSA, "medium", MOCK_PROVIDER_ID, MOCK_MODEL_ID);
        Long roundId = session.getRounds().get(0).getId();

        turnOrchestrator.beginRound(roundId, TurnSink.noop());

        // 2. Script turn 1: degenerate case where model calls tool but emits ZERO text
        mockProvider.enqueueResponse(List.of(
                LlmEvent.toolCall(InterviewerTools.ADVANCE_PHASE, "call_adv_silent",
                        Map.of("target_phase", "CLARIFYING", "rationale", "Ready to clarify")),
                LlmEvent.usage(new LlmEvent.Usage(100, 20, 0, 0)),
                LlmEvent.done()
        ));

        // Script turn 2 (continuation retry): model produces the skipped words with tools withheld
        mockProvider.enqueueResponse(List.of(
                LlmEvent.textDelta("Let's first clarify the expected input format and scale."),
                LlmEvent.usage(new LlmEvent.Usage(130, 25, 0, 0)),
                LlmEvent.done()
        ));

        // 3. Handle candidate turn
        turnOrchestrator.handleCandidateTurn(roundId, "I understand the problem statement.", null, TurnSink.noop());

        // 4. Invariant 4 assertions:
        // (a) Provider was called twice (initial silent call + continuation retry)
        assertThat(mockProvider.getCallCount()).isEqualTo(2);

        // (b) Continuation request had tools withheld (empty tools list)
        LlmRequest continuationReq = mockProvider.getRecordedRequests().get(1);
        assertThat(continuationReq.getTools()).isEmpty();

        // (c) Interviewer turn persisted with the words that arrived during continuation
        List<TranscriptTurn> turns = transcriptService.getTranscript(roundId);
        TranscriptTurn lastTurn = turns.get(turns.size() - 1);
        assertThat(lastTurn.getRole()).isEqualTo(TurnRole.INTERVIEWER);
        assertThat(lastTurn.getContent()).isEqualTo("Let's first clarify the expected input format and scale.");

        // (d) Usage was aggregated across both calls (100 + 130 = 230 input, 20 + 25 = 45 output)
        List<LlmCall> calls = llmCallRepo.findByRoundIdOrderByCreatedAtAsc(roundId);
        assertThat(calls).isNotEmpty();
        LlmCall lastCall = calls.get(calls.size() - 1);
        assertThat(lastCall.getInputTokens()).isEqualTo(230);
        assertThat(lastCall.getOutputTokens()).isEqualTo(45);

        // (e) Phase transition still succeeded
        SessionRound round = roundRepo.findById(roundId).orElseThrow();
        assertThat(round.getPhase()).isEqualTo(RoundPhase.CLARIFYING);
    }

    @Test
    void finalEvaluation_createsSessionReportAndEpochTaggedSnapshot() {
        InterviewSession session = sessionManager.createSingleModuleSession(
                "google", ModuleType.DSA, "medium", MOCK_PROVIDER_ID, MOCK_MODEL_ID);
        Long roundId = session.getRounds().get(0).getId();

        turnOrchestrator.beginRound(roundId, TurnSink.noop());
        sessionManager.completeRound(roundId);

        // The evaluator deliberately uses the same scripted provider. Switching it bumps the
        // comparability epoch, which the snapshot must preserve permanently.
        int evaluatorEpoch = settingsStore.setEvaluator(MOCK_PROVIDER_ID, MOCK_MODEL_ID);
        mockProvider.enqueueResponse(List.of(
                LlmEvent.toolCall(EvaluationTools.SUBMIT_EVALUATION, "call_eval_1", Map.of(
                        "scores", Map.of("clarification", 4, "correctness", 4),
                        "strengths", List.of("Asked a relevant clarification question."),
                        "gaps", List.of("Did not yet discuss edge cases."),
                        "narrative_md", "A clear start with room to deepen the solution.")),
                LlmEvent.usage(new LlmEvent.Usage(90, 30, 0, 0)),
                LlmEvent.done()
        ));

        roundEvaluator.evaluate(roundId);

        assertThat(sessionReportRepo.findBySessionId(session.getId())).isPresent();
        List<ReadinessSnapshot> snapshots = readinessSnapshotRepo
                .findByCompanyProfileIdAndModuleTypeOrderByTakenAtDesc("google", "dsa");
        assertThat(snapshots).hasSize(1);
        assertThat(snapshots.get(0).getComparabilityEpoch()).isEqualTo(evaluatorEpoch);
    }

    /**
     * H5 (docs/TASKS.md, PROJECT_PLAN.md §3): the evaluator's system prompt must carry the
     * calibration anchors, clearly separated from the module's own rubric text so the model
     * cannot confuse the illustrative examples with this round's real dimensions or evidence.
     */
    @Test
    void evaluatorSystemPromptIncludesCalibrationAnchorsAfterTheRubric() {
        // Deliberately not "google" — finalEvaluation_createsSessionReportAndEpochTaggedSnapshot
        // asserts exactly one readiness snapshot for google/dsa, and this class doesn't roll
        // back between tests, so reusing that company+module pair here would break its count.
        InterviewSession session = sessionManager.createSingleModuleSession(
                "microsoft", ModuleType.DSA, "medium", MOCK_PROVIDER_ID, MOCK_MODEL_ID);
        Long roundId = session.getRounds().get(0).getId();

        turnOrchestrator.beginRound(roundId, TurnSink.noop());
        sessionManager.completeRound(roundId);

        settingsStore.setEvaluator(MOCK_PROVIDER_ID, MOCK_MODEL_ID);
        mockProvider.enqueueResponse(List.of(
                LlmEvent.toolCall(EvaluationTools.SUBMIT_EVALUATION, "call_eval_2", Map.of(
                        "scores", Map.of("clarification", 3),
                        "strengths", List.of("Engaged with the problem."),
                        "gaps", List.of("Limited depth."),
                        "narrative_md", "Adequate.")),
                LlmEvent.usage(new LlmEvent.Usage(90, 30, 0, 0)),
                LlmEvent.done()
        ));

        roundEvaluator.evaluate(roundId);

        LlmRequest lastRequest = mockProvider.getRecordedRequests()
                .get(mockProvider.getRecordedRequests().size() - 1);
        String system = lastRequest.getSystemMessages().get(0).getContent();

        // The anchor block is present, clearly labelled, and explicitly disclaims being this
        // round's real candidate — not just present, but present as its own labelled section.
        assertThat(system)
                .contains("CALIBRATION EXAMPLES")
                .contains("not this round's candidate")
                .contains("BELOW THE BAR")
                .contains("ABOVE THE BAR");

        // Structural separation: the module's rubric (a real dimension string) comes first,
        // the calibration block after it — never interleaved with or ahead of the rubric.
        int rubricIndex = system.indexOf("clarification");
        int anchorIndex = system.indexOf("CALIBRATION EXAMPLES");
        assertThat(rubricIndex).isGreaterThanOrEqualTo(0);
        assertThat(anchorIndex).isGreaterThan(rubricIndex);

        // The anchors live in the system message only — never inside the evidence/conversation
        // message that carries this round's actual signals and transcript.
        String evidenceMessage = lastRequest.getConversationMessages().get(0).getContent();
        assertThat(evidenceMessage).doesNotContain("CALIBRATION EXAMPLES");
    }

    @Test
    void fullLoop_preparesOnlyTheNextRoundWithPrivateStableHandoff() {
        InterviewSession session = sessionManager.createSession(
                "google", SessionMode.FULL_LOOP, MOCK_PROVIDER_ID, MOCK_MODEL_ID);
        List<SessionRound> rounds = roundRepo.findBySessionIdOrderByOrdinalAsc(session.getId());
        SessionRound first = rounds.get(0);
        SessionRound second = rounds.get(1);

        // The profile's explicit curve is retained, and a stale client cannot skip over round 1.
        assertThat(first.getDifficultyTarget()).isEqualTo("medium-hard");
        assertThat(second.getDifficultyTarget()).isEqualTo("hard");
        assertThatThrownBy(() -> sessionManager.startRound(second.getId()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preceding round");

        sessionManager.startRound(first.getId());
        sessionManager.completeRound(first.getId());

        RoundEvaluation evaluation = new RoundEvaluation(first);
        evaluation.setStrengths("[\"Structured the approach clearly\"]");
        evaluation.setGaps("[\"Did not test boundary cases\"]");
        SessionManager.RoundAdvance advance = sessionManager.prepareNextRound(first.getId(), evaluation).orElseThrow();

        assertThat(advance.nextRound().getId()).isEqualTo(second.getId());
        assertThat(advance.skippedRounds()).isEmpty();
        SessionRound prepared = roundRepo.findById(second.getId()).orElseThrow();
        assertThat(prepared.getStatus()).isEqualTo(RoundStatus.PENDING);
        assertThat(prepared.getCarryOverBrief())
                .contains("Structured the approach clearly")
                .contains("Did not test boundary cases");

        RoundContext nextContext = contextFactory.build(second.getId(), 0);
        assertThat(moduleRegistry.require(second.getModuleType()).persona(nextContext))
                .contains("Private panel handoff")
                .contains("do not mention this note to the candidate");

        SessionRound started = sessionManager.startRound(second.getId());
        assertThat(started.getStatus()).isEqualTo(RoundStatus.IN_PROGRESS);
    }
}
