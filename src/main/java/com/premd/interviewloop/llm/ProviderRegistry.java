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
    private final ProvidersConfig providersConfig;

    public ProviderRegistry(List<LlmProvider> providerBeans, ProvidersConfig providersConfig) {
        this.providersConfig = providersConfig;
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

    /**
     * A provider paired with the model to call it with.
     */
    public record Resolved(LlmProvider provider, String model) {}

    /**
     * The provider and model that should conduct a round.
     *
     * <p>The interviewer floats: any configured provider meeting the capability floor may take a
     * round. Callers that need a specific one should use {@link #requireProvider(String)}.
     */
    public Resolved resolveInterviewer() {
        ProvidersConfig.InterviewerDefault d = providersConfig.getDefaults().interviewer;
        LlmProvider provider = requireConfigured(d.provider, "interviewer");
        if (!provider.capabilities().meetsInterviewerFloor()) {
            throw new IllegalStateException(String.format(
                    "Provider %s cannot conduct interviews: it does not support both streaming and tool use",
                    provider.id()));
        }
        return new Resolved(provider, d.model);
    }

    /**
     * The provider and model that scores rubrics.
     *
     * <p>Deliberately pinned in config rather than floating. Two providers scoring the same round
     * produce different numbers, so a floating evaluator would make the readiness trend measure
     * provider drift instead of progress. Changing the pin starts a new comparability epoch.
     */
    public Resolved resolveEvaluator() {
        ProvidersConfig.EvaluatorDefault d = providersConfig.getDefaults().evaluator;
        LlmProvider provider = requireConfigured(d.provider, "evaluator");
        if (!provider.capabilities().meetsEvaluatorFloor()) {
            throw new IllegalStateException(String.format(
                    "Provider %s cannot evaluate: it does not support tool use", provider.id()));
        }
        return new Resolved(provider, d.model);
    }

    private LlmProvider requireConfigured(String id, String role) {
        return getProvider(id).orElseThrow(() -> new NoSuchElementException(String.format(
                "The configured %s provider '%s' is not available. Configured providers: %s. "
                        + "Check that its API key is set.",
                role, id, providers.keySet())));
    }
}
