package com.premd.interviewloop.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves a *usable* LLM provider for a turn: a factory whose provider id has a key
 * available, built fresh from that key. Never caches the built client — construction is
 * cheap (no network call) and rebuilding means a key pasted through Settings takes effect
 * on the very next call, with no stale-client bug to reason about.
 *
 * For listing providers regardless of configuration state, see {@link ProviderCatalog}.
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final Map<String, ProviderFactory> factories = new LinkedHashMap<>();
    private final ProviderKeyStore keyStore;
    private final AppSettingsStore settingsStore;

    public ProviderRegistry(List<ProviderFactory> factoryBeans, ProviderKeyStore keyStore,
                             AppSettingsStore settingsStore) {
        this.keyStore = keyStore;
        this.settingsStore = settingsStore;
        for (ProviderFactory factory : factoryBeans) {
            factories.put(factory.id(), factory);
            log.info("Registered LLM provider factory: {}", factory.id());
        }
        if (factories.isEmpty()) {
            log.warn("No LLM provider factories registered. The app will start but interviews cannot be conducted.");
        }
    }

    /** The live provider for this id, or empty if it isn't implemented or has no key. */
    public Optional<LlmProvider> getProvider(String id) {
        ProviderFactory factory = factories.get(id);
        if (factory == null) {
            return Optional.empty();
        }
        return keyStore.resolve(id).map(factory::create);
    }

    public LlmProvider requireProvider(String id) {
        return getProvider(id).orElseThrow(() ->
                new NoSuchElementException("LLM provider not available: " + id));
    }

    /**
     * A provider paired with the model to call it with.
     */
    public record Resolved(LlmProvider provider, String model) {}

    /**
     * The provider and model that should conduct a round, per the current interviewer
     * binding in {@link AppSettingsStore} (UI-overridable; falls back to config/providers.yaml).
     */
    public Resolved resolveInterviewer() {
        AppSettingsStore.RoleBinding binding = settingsStore.interviewerBinding();
        LlmProvider provider = requireConfigured(binding.provider(), "interviewer");
        if (!provider.capabilities().meetsInterviewerFloor()) {
            throw new IllegalStateException(String.format(
                    "Provider %s cannot conduct interviews: it does not support both streaming and tool use",
                    provider.id()));
        }
        return new Resolved(provider, binding.model());
    }

    /**
     * The provider and model that scores rubrics.
     *
     * <p>Deliberately pinned rather than floating with the interviewer. Two providers scoring
     * the same round produce different numbers, so a floating evaluator would make the
     * readiness trend measure provider drift instead of progress. Changing the pin (via
     * {@link AppSettingsStore#setEvaluator}) starts a new comparability epoch.
     */
    public Resolved resolveEvaluator() {
        AppSettingsStore.RoleBinding binding = settingsStore.evaluatorBinding();
        LlmProvider provider = requireConfigured(binding.provider(), "evaluator");
        if (!provider.capabilities().meetsEvaluatorFloor()) {
            throw new IllegalStateException(String.format(
                    "Provider %s cannot evaluate: it does not support tool use", provider.id()));
        }
        return new Resolved(provider, binding.model());
    }

    private LlmProvider requireConfigured(String id, String role) {
        return getProvider(id).orElseThrow(() -> new NoSuchElementException(String.format(
                "The configured %s provider '%s' is not available. Configured providers: %s. "
                        + "Check that its API key is set.",
                role, id, configuredIds())));
    }

    private List<String> configuredIds() {
        return factories.keySet().stream().filter(keyStore::isConfigured).collect(Collectors.toList());
    }
}
