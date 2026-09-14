package com.premd.interviewloop.session;

import com.premd.interviewloop.domain.InterviewSession;
import com.premd.interviewloop.domain.LlmCall;
import com.premd.interviewloop.domain.SessionRound;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.domain.enums.RoundPhase;
import com.premd.interviewloop.domain.repository.LlmCallRepository;
import com.premd.interviewloop.llm.AppSettingsStore;
import com.premd.interviewloop.llm.LlmEvent;
import com.premd.interviewloop.llm.ProviderKeyStore;
import com.premd.interviewloop.testsupport.ScriptedProviderSupport.ScriptedLlmProvider;
import com.premd.interviewloop.testsupport.ScriptedProviderSupport.ScriptedProviderFactory;
import com.premd.interviewloop.testsupport.ScriptedProviderSupport.TestProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.premd.interviewloop.testsupport.ScriptedProviderSupport.MOCK_MODEL_ID;
import static com.premd.interviewloop.testsupport.ScriptedProviderSupport.MOCK_PROVIDER_ID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-7 (PROJECT_PLAN.md §5.3): a session that crosses the configured cost ceiling gets a
 * one-time warning, and is never blocked. Reuses the shared scripted provider harness
 * ({@link com.premd.interviewloop.testsupport.ScriptedProviderSupport}) rather than
 * duplicating it.
 *
 * <p>The scripted mock provider has no pricing entry in {@code config/providers.yaml}, so
 * calls it makes always cost $0 — real per-call cost is exercised elsewhere (T1). This test
 * seeds an {@link LlmCall} row directly to simulate prior spend, then asserts the ceiling
 * check on the *next* turn behaves correctly — crossed once, warned once, never repeated.
 */
@SpringBootTest
@Import(TestProviderConfig.class)
@TestPropertySource(properties = "app.cost-ceiling-usd=0.01")
class CostCeilingTest {

    @Autowired
    private TurnOrchestrator turnOrchestrator;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private LlmCallRepository llmCallRepo;

    @Autowired
    private ProviderKeyStore keyStore;

    @Autowired
    private AppSettingsStore settingsStore;

    @Autowired
    private ScriptedProviderFactory providerFactory;

    private ScriptedLlmProvider mockProvider;

    /** Captures every costWarning call; everything else is a no-op. */
    private static class RecordingSink implements TurnSink {
        final List<double[]> warnings = new ArrayList<>();

        @Override public void textDelta(String text) {}
        @Override public void toolCall(String name, String id, Map<String, Object> arguments) {}
        @Override public void phaseAdvanced(RoundPhase phase) {}
        @Override public void roundCompleted(Long roundId) {}
        @Override public void usage(int in, int out, int cacheRead, double cost) {}
        @Override public void turnComplete(Long roundId) {}
        @Override public void error(String message) {}

        @Override
        public void costWarning(double sessionCostUsd, double ceilingUsd) {
            warnings.add(new double[]{sessionCostUsd, ceilingUsd});
        }
    }

    @BeforeEach
    void setUp() {
        mockProvider = providerFactory.getProvider();
        mockProvider.reset();
        keyStore.putUiKey(MOCK_PROVIDER_ID, "fake-test-key");
        settingsStore.setInterviewer(MOCK_PROVIDER_ID, MOCK_MODEL_ID);
    }

    @Test
    void warnsExactlyOnceWhenSessionCrossesTheConfiguredCeiling() {
        InterviewSession session = sessionManager.createSingleModuleSession(
                "google", ModuleType.DSA, "medium", MOCK_PROVIDER_ID, MOCK_MODEL_ID);
        SessionRound round = session.getRounds().get(0);

        turnOrchestrator.beginRound(round.getId(), TurnSink.noop());

        // Simulate prior spend this session: $0.02, already over the test's $0.01 ceiling.
        LlmCall priorSpend = new LlmCall(MOCK_PROVIDER_ID, MOCK_MODEL_ID, "interviewer");
        priorSpend.setRound(round);
        priorSpend.setCostEstimateUsd(0.02);
        llmCallRepo.save(priorSpend);

        mockProvider.enqueueResponse(List.of(
                LlmEvent.textDelta("Let's continue."),
                LlmEvent.usage(new LlmEvent.Usage(10, 10, 0, 0)),
                LlmEvent.done()));

        RecordingSink sink = new RecordingSink();
        turnOrchestrator.handleCandidateTurn(round.getId(), "Okay.", null, sink);

        assertThat(sink.warnings).hasSize(1);
        assertThat(sink.warnings.get(0)[0]).isGreaterThanOrEqualTo(0.02);
        assertThat(sink.warnings.get(0)[1]).isEqualTo(0.01);

        // A second turn, still over the ceiling, must not warn again.
        mockProvider.enqueueResponse(List.of(
                LlmEvent.textDelta("Still going."),
                LlmEvent.usage(new LlmEvent.Usage(10, 10, 0, 0)),
                LlmEvent.done()));
        turnOrchestrator.handleCandidateTurn(round.getId(), "Still here.", null, sink);

        assertThat(sink.warnings).hasSize(1);
    }

    @Test
    void staysSilentWhileUnderTheCeiling() {
        InterviewSession session = sessionManager.createSingleModuleSession(
                "google", ModuleType.DSA, "medium", MOCK_PROVIDER_ID, MOCK_MODEL_ID);
        SessionRound round = session.getRounds().get(0);

        turnOrchestrator.beginRound(round.getId(), TurnSink.noop());

        // No prior spend seeded, and the mock provider's own calls always cost $0 (no pricing
        // entry for it in config/providers.yaml) — nowhere near the $0.01 ceiling.
        mockProvider.enqueueResponse(List.of(
                LlmEvent.textDelta("Let's continue."),
                LlmEvent.usage(new LlmEvent.Usage(10, 10, 0, 0)),
                LlmEvent.done()));

        RecordingSink sink = new RecordingSink();
        turnOrchestrator.handleCandidateTurn(round.getId(), "Okay.", null, sink);

        assertThat(sink.warnings).isEmpty();
    }
}
