package com.premd.interviewloop.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Discovers and manages LLM providers. Filters by API key presence
 * and capability floor before exposing providers to the rest of the app.
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<String, LlmProvider> providers = new LinkedHashMap<>();

    public ProviderRegistry(List<LlmProvider> providerBeans) {
        for (LlmProvider provider : providerBeans) {
            providers.put(provider.id(), provider);
            log.info("Registered LLM provider: {} ({})", provider.id(), provider.displayName());
        }
        if (providers.isEmpty()) {
            log.warn("No LLM providers registered. The app will start but interviews cannot be conducted.");
        }
    }

    /** Get a specific provider by id. */
    public Optional<LlmProvider> getProvider(String id) {
        return Optional.ofNullable(providers.get(id));
    }

    /** Get the provider or throw. */
    public LlmProvider requireProvider(String id) {
        return getProvider(id).orElseThrow(() ->
                new NoSuchElementException("LLM provider not available: " + id));
    }

    /** All registered providers. */
    public Collection<LlmProvider> getAllProviders() {
        return Collections.unmodifiableCollection(providers.values());
    }

    /** Providers that meet the interviewer capability floor. */
    public List<LlmProvider> getInterviewerProviders() {
        return providers.values().stream()
                .filter(p -> p.capabilities().meetsInterviewerFloor())
                .collect(Collectors.toList());
    }

    /** Providers that meet the evaluator capability floor. */
    public List<LlmProvider> getEvaluatorProviders() {
        return providers.values().stream()
                .filter(p -> p.capabilities().meetsEvaluatorFloor())
                .collect(Collectors.toList());
    }
}
