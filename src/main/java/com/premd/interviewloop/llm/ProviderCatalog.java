package com.premd.interviewloop.llm;

import com.premd.interviewloop.llm.Capabilities.PromptCachingMode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Lists every provider config/providers.yaml knows about, whether or not it has a key —
 * that is what lets the UI show "add a key to use Claude" instead of Claude being absent.
 * {@link ProviderRegistry} is the separate, narrower thing that resolves a *usable*
 * provider for a turn; this is metadata only and never builds a client.
 */
@Component
public class ProviderCatalog {

    private final ProvidersConfig providersConfig;
    private final ProviderKeyStore keyStore;
    private final Map<String, ProviderFactory> factories;

    public ProviderCatalog(ProvidersConfig providersConfig, ProviderKeyStore keyStore,
                            List<ProviderFactory> factoryBeans) {
        this.providersConfig = providersConfig;
        this.keyStore = keyStore;
        this.factories = factoryBeans.stream()
                .collect(Collectors.toMap(ProviderFactory::id, f -> f));
    }

    public List<ProviderInfo> list() {
        return providersConfig.getProviders().stream().map(this::toInfo).toList();
    }

    public ProviderInfo get(String id) {
        return providersConfig.getProviderEntry(id)
                .map(this::toInfo)
                .orElseThrow(() -> new java.util.NoSuchElementException("Unknown provider: " + id));
    }

    /** True if this id names a provider with an adapter actually built. */
    public boolean isImplemented(String id) {
        return factories.containsKey(id);
    }

    private ProviderInfo toInfo(ProvidersConfig.ProviderEntry entry) {
        Capabilities capabilities = toCapabilities(entry.capabilities);
        List<ProviderInfo.ModelInfo> models = entry.models == null ? List.of() :
                entry.models.stream()
                        .map(m -> new ProviderInfo.ModelInfo(m.id, m.roleHint, m.contextTokens, m.notes))
                        .toList();
        boolean implemented = factories.containsKey(entry.id);
        return new ProviderInfo(
                entry.id,
                entry.displayName,
                entry.enabled && implemented,
                keyStore.isConfigured(entry.id),
                keyStore.sourceFor(entry.id),
                keyStore.maskedKey(entry.id).orElse(null),
                capabilities,
                models,
                capabilities.meetsInterviewerFloor(),
                capabilities.meetsEvaluatorFloor()
        );
    }

    private Capabilities toCapabilities(Map<String, Object> raw) {
        if (raw == null) {
            return new Capabilities(false, false, PromptCachingMode.NONE, false);
        }
        boolean streaming = bool(raw.get("streaming"));
        boolean toolUse = bool(raw.get("tool_use"));
        boolean vision = bool(raw.get("vision"));
        PromptCachingMode caching = switch (String.valueOf(raw.getOrDefault("prompt_caching", "none"))) {
            case "explicit" -> PromptCachingMode.EXPLICIT;
            case "automatic" -> PromptCachingMode.AUTOMATIC;
            default -> PromptCachingMode.NONE;
        };
        return new Capabilities(streaming, toolUse, caching, vision);
    }

    private boolean bool(Object value) {
        return Boolean.TRUE.equals(value);
    }
}
