package com.premd.interviewloop.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premd.interviewloop.domain.InterviewSession;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.interviewer.InterviewerTools;
import com.premd.interviewloop.llm.AppSettingsStore;
import com.premd.interviewloop.llm.LlmEvent;
import com.premd.interviewloop.llm.ProviderKeyStore;
import com.premd.interviewloop.session.SessionManager;
import com.premd.interviewloop.testsupport.ScriptedProviderSupport.ScriptedLlmProvider;
import com.premd.interviewloop.testsupport.ScriptedProviderSupport.ScriptedProviderFactory;
import com.premd.interviewloop.testsupport.ScriptedProviderSupport.TestProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.premd.interviewloop.testsupport.ScriptedProviderSupport.MOCK_MODEL_ID;
import static com.premd.interviewloop.testsupport.ScriptedProviderSupport.MOCK_PROVIDER_ID;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * C2 (docs/COMPLETION_PLAN.md): drives {@code /ws/interview} over an actual WebSocket
 * connection with real JSON frames, not just {@link com.premd.interviewloop.session.TurnOrchestrator}
 * directly — {@code TurnOrchestratorIntegrationTest} already covers the latter. This is the
 * frame-ordering and wire-format contract the React client actually parses
 * ({@code web/src/ws/frames.ts}).
 *
 * <p>No API key needed: the scripted provider (see {@code ScriptedProviderSupport}) never
 * makes a network call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestProviderConfig.class)
class InterviewWebSocketHandlerTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SessionManager sessionManager;

    @Autowired
    private ProviderKeyStore keyStore;

    @Autowired
    private AppSettingsStore settingsStore;

    @Autowired
    private ScriptedProviderFactory providerFactory;

    private ScriptedLlmProvider mockProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StandardWebSocketClient wsClient = new StandardWebSocketClient();

    @BeforeEach
    void setUp() {
        mockProvider = providerFactory.getProvider();
        mockProvider.reset();
        keyStore.putUiKey(MOCK_PROVIDER_ID, "fake-test-key");
        settingsStore.setInterviewer(MOCK_PROVIDER_ID, MOCK_MODEL_ID);
    }

    /** Collects every inbound frame's raw JSON, in arrival order, for polling from the test thread. */
    private static class CollectingHandler extends TextWebSocketHandler {
        final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            frames.add(message.getPayload());
        }
    }

    private WebSocketSession connect(CollectingHandler handler) throws Exception {
        return wsClient.execute(handler, "ws://localhost:{port}/ws/interview", port).get(5, TimeUnit.SECONDS);
    }

    private JsonNode nextFrame(CollectingHandler handler) throws Exception {
        String raw = handler.frames.poll(5, TimeUnit.SECONDS);
        assertThat(raw).as("expected a frame within 5s, got none").isNotNull();
        return objectMapper.readTree(raw);
    }

    private void send(WebSocketSession session, String json) throws Exception {
        session.sendMessage(new TextMessage(json));
    }

    private Long startDsaRound() {
        InterviewSession session = sessionManager.createSingleModuleSession(
                "google", ModuleType.DSA, "medium", MOCK_PROVIDER_ID, MOCK_MODEL_ID);
        return session.getRounds().get(0).getId();
    }

    @Test
    void startRound_deliversOpeningBriefThenTurnCompleteThenRoundStarted() throws Exception {
        Long roundId = startDsaRound();
        CollectingHandler handler = new CollectingHandler();
        WebSocketSession ws = connect(handler);

        send(ws, objectMapper.writeValueAsString(Map.of("type", "start_round", "roundId", roundId)));

        // Real wire order, not the intuitive one: InterviewWebSocketHandler.handleStartRound
        // runs TurnOrchestrator.beginRound (which streams the opening brief as text_delta,
        // then turn_complete) to completion BEFORE it sends round_started itself. The React
        // client (App.tsx) handles each frame type independently regardless of arrival order
        // — round_started only sets a start timestamp — so this ordering is real but benign;
        // documented here rather than assumed.
        JsonNode delta = nextFrame(handler);
        assertThat(delta.get("type").asText()).isEqualTo("text_delta");
        assertThat(delta.get("text").asText()).isNotBlank();

        JsonNode complete = nextFrame(handler);
        assertThat(complete.get("type").asText()).isEqualTo("turn_complete");
        assertThat(complete.get("roundId").asLong()).isEqualTo(roundId);

        JsonNode started = nextFrame(handler);
        assertThat(started.get("type").asText()).isEqualTo("round_started");
        assertThat(started.get("roundId").asLong()).isEqualTo(roundId);

        ws.close();
    }

    @Test
    void candidateTurn_streamsAckDeltasToolCallPhaseAdvancedUsageThenComplete() throws Exception {
        Long roundId = startDsaRound();
        CollectingHandler startHandler = new CollectingHandler();
        WebSocketSession startWs = connect(startHandler);
        send(startWs, objectMapper.writeValueAsString(Map.of("type", "start_round", "roundId", roundId)));
        nextFrame(startHandler); // text_delta
        nextFrame(startHandler); // turn_complete
        nextFrame(startHandler); // round_started
        startWs.close();

        mockProvider.enqueueResponse(List.of(
                LlmEvent.textDelta("Good thought. "),
                LlmEvent.textDelta("What about the edge cases?"),
                LlmEvent.toolCall(InterviewerTools.ADVANCE_PHASE, "call_1",
                        Map.of("target_phase", "CLARIFYING", "rationale", "engaged")),
                LlmEvent.usage(new LlmEvent.Usage(120, 40, 0, 0)),
                LlmEvent.done()));

        CollectingHandler handler = new CollectingHandler();
        WebSocketSession ws = connect(handler);
        send(ws, objectMapper.writeValueAsString(Map.of(
                "type", "candidate_turn", "roundId", roundId, "text", "Should I handle negatives?")));

        JsonNode ack = nextFrame(handler);
        assertThat(ack.get("type").asText()).isEqualTo("turn_ack");
        assertThat(ack.get("roundId").asLong()).isEqualTo(roundId);

        StringBuilder streamed = new StringBuilder();
        JsonNode frame = nextFrame(handler);
        while (frame.get("type").asText().equals("text_delta")) {
            streamed.append(frame.get("text").asText());
            frame = nextFrame(handler);
        }
        assertThat(streamed.toString()).isEqualTo("Good thought. What about the edge cases?");

        assertThat(frame.get("type").asText()).isEqualTo("tool_call");
        assertThat(frame.get("name").asText()).isEqualTo(InterviewerTools.ADVANCE_PHASE);

        JsonNode phaseAdvanced = nextFrame(handler);
        assertThat(phaseAdvanced.get("type").asText()).isEqualTo("phase_advanced");
        assertThat(phaseAdvanced.get("phase").asText()).isEqualTo("CLARIFYING");

        JsonNode usage = nextFrame(handler);
        assertThat(usage.get("type").asText()).isEqualTo("usage");
        assertThat(usage.get("inputTokens").asInt()).isEqualTo(120);
        assertThat(usage.get("outputTokens").asInt()).isEqualTo(40);

        JsonNode complete = nextFrame(handler);
        assertThat(complete.get("type").asText()).isEqualTo("turn_complete");

        ws.close();
    }

    @Test
    void candidateTurn_silentToolOnlyTurn_stillDeliversWordsOverTheSocket() throws Exception {
        Long roundId = startDsaRound();
        CollectingHandler startHandler = new CollectingHandler();
        WebSocketSession startWs = connect(startHandler);
        send(startWs, objectMapper.writeValueAsString(Map.of("type", "start_round", "roundId", roundId)));
        nextFrame(startHandler);
        nextFrame(startHandler);
        nextFrame(startHandler);
        startWs.close();

        // Turn 1: model calls a tool and says nothing (AGENTS.md invariant 4's degenerate case).
        mockProvider.enqueueResponse(List.of(
                LlmEvent.toolCall(InterviewerTools.ADVANCE_PHASE, "call_silent",
                        Map.of("target_phase", "CLARIFYING", "rationale", "ready")),
                LlmEvent.usage(new LlmEvent.Usage(90, 15, 0, 0)),
                LlmEvent.done()));
        // Turn 2 (continuation retry, tools withheld): the words that were missing arrive.
        mockProvider.enqueueResponse(List.of(
                LlmEvent.textDelta("Let's clarify the input format first."),
                LlmEvent.usage(new LlmEvent.Usage(100, 20, 0, 0)),
                LlmEvent.done()));

        CollectingHandler handler = new CollectingHandler();
        WebSocketSession ws = connect(handler);
        send(ws, objectMapper.writeValueAsString(Map.of(
                "type", "candidate_turn", "roundId", roundId, "text", "I understand the problem.")));

        assertThat(nextFrame(handler).get("type").asText()).isEqualTo("turn_ack");
        assertThat(nextFrame(handler).get("type").asText()).isEqualTo("tool_call");

        // A client that never saw the silent-turn retry sees exactly what it needs to: a real
        // text_delta still arrives, never literal silence followed straight by turn_complete.
        JsonNode delta = nextFrame(handler);
        assertThat(delta.get("type").asText()).isEqualTo("text_delta");
        assertThat(delta.get("text").asText()).isEqualTo("Let's clarify the input format first.");

        ws.close();
    }

    @Test
    void unknownMessageType_returnsErrorFrame() throws Exception {
        CollectingHandler handler = new CollectingHandler();
        WebSocketSession ws = connect(handler);
        send(ws, objectMapper.writeValueAsString(Map.of("type", "not_a_real_type")));

        JsonNode error = nextFrame(handler);
        assertThat(error.get("type").asText()).isEqualTo("error");
        assertThat(error.get("message").asText()).contains("Unknown message type");

        ws.close();
    }

    @Test
    void candidateTurn_missingRoundId_returnsErrorFrame() throws Exception {
        CollectingHandler handler = new CollectingHandler();
        WebSocketSession ws = connect(handler);
        send(ws, objectMapper.writeValueAsString(Map.of("type", "candidate_turn", "text", "hi")));

        JsonNode error = nextFrame(handler);
        assertThat(error.get("type").asText()).isEqualTo("error");
        assertThat(error.get("message").asText()).contains("roundId is required");

        ws.close();
    }

    @Test
    void reconnectAndResendStartRound_isIdempotent_noSecondOpeningBrief() throws Exception {
        Long roundId = startDsaRound();

        CollectingHandler first = new CollectingHandler();
        WebSocketSession firstWs = connect(first);
        send(firstWs, objectMapper.writeValueAsString(Map.of("type", "start_round", "roundId", roundId)));
        nextFrame(first); // text_delta: the opening brief
        nextFrame(first); // turn_complete
        nextFrame(first); // round_started
        firstWs.close();

        // A reconnecting client resending start_round for the same (already-pinned) round must
        // not re-run the opening brief — TurnOrchestrator.beginRound's documented idempotency
        // on questionSlug. Confirmed here over the actual socket, not just at the service layer.
        CollectingHandler second = new CollectingHandler();
        WebSocketSession secondWs = connect(second);
        send(secondWs, objectMapper.writeValueAsString(Map.of("type", "start_round", "roundId", roundId)));

        JsonNode frame = nextFrame(second);
        assertThat(frame.get("type").asText())
                .as("no opening brief re-sent on a repeated start_round for an already-pinned round")
                .isEqualTo("turn_complete");

        JsonNode started = nextFrame(second);
        assertThat(started.get("type").asText()).isEqualTo("round_started");

        secondWs.close();
    }
}
