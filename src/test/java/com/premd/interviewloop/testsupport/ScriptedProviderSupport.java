package com.premd.interviewloop.testsupport;

import com.premd.interviewloop.llm.Capabilities;
import com.premd.interviewloop.llm.LlmEvent;
import com.premd.interviewloop.llm.LlmProvider;
import com.premd.interviewloop.llm.LlmRequest;
import com.premd.interviewloop.llm.ProviderFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared scripted, in-memory {@link LlmProvider} for tests that need to drive the turn loop
 * without any network call or API key — zero live-quota burned.
 *
 * <p>Originally lived inline in {@code TurnOrchestratorIntegrationTest} (package {@code
 * session}); extracted here, public, so tests in other packages (e.g. {@code transport}'s
 * WebSocket contract tests) can reuse the exact same harness instead of each defining their
 * own copy of a mock provider.
 */
public final class ScriptedProviderSupport {

    public static final String MOCK_PROVIDER_ID = "mock-test-provider";
    public static final String MOCK_MODEL_ID = "mock-model-v1";

    private ScriptedProviderSupport() {
    }

    @TestConfiguration
    public static class TestProviderConfig {
        @Bean
        public ScriptedProviderFactory scriptedProviderFactory() {
            return new ScriptedProviderFactory();
        }
    }

    public static class ScriptedProviderFactory implements ProviderFactory {
        private final ScriptedLlmProvider provider = new ScriptedLlmProvider();

        @Override
        public String id() {
            return MOCK_PROVIDER_ID;
        }

        @Override
        public LlmProvider create(String apiKey) {
            return provider;
        }

        public ScriptedLlmProvider getProvider() {
            return provider;
        }
    }

    public static class ScriptedLlmProvider implements LlmProvider {
        private final Queue<List<LlmEvent>> responseQueue = new ConcurrentLinkedQueue<>();
        private final List<LlmRequest> recordedRequests = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger callCount = new AtomicInteger(0);

        public void enqueueResponse(List<LlmEvent> events) {
            responseQueue.add(events);
        }

        public void reset() {
            responseQueue.clear();
            recordedRequests.clear();
            callCount.set(0);
        }

        public int getCallCount() {
            return callCount.get();
        }

        public List<LlmRequest> getRecordedRequests() {
            return recordedRequests;
        }

        @Override
        public String id() {
            return MOCK_PROVIDER_ID;
        }

        @Override
        public String displayName() {
            return "Scripted Mock Provider";
        }

        @Override
        public Capabilities capabilities() {
            return new Capabilities(true, true, Capabilities.PromptCachingMode.NONE, false);
        }

        @Override
        public Flux<LlmEvent> stream(LlmRequest request) {
            callCount.incrementAndGet();
            recordedRequests.add(request);
            List<LlmEvent> events = responseQueue.poll();
            if (events == null) {
                events = List.of(
                        LlmEvent.textDelta("Default mock response"),
                        LlmEvent.usage(new LlmEvent.Usage(10, 10, 0, 0)),
                        LlmEvent.done());
            }
            return Flux.fromIterable(events);
        }
    }
}
