package com.premd.interviewloop.llm;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds API keys supplied through the UI, in memory only — never written to disk, the
 * database, or a log line. Lost on restart by design: persistence (encrypted-at-rest or
 * otherwise) is D-9 in PROJECT_PLAN.md §5.2, still open, and not decided by this class.
 *
 * A UI-supplied key always wins over an environment one for the same provider, so a key
 * pasted in Settings can be used to try an alternative without touching the process env.
 */
@Component
public class ProviderKeyStore {

    private final ProvidersConfig providersConfig;
    private final Map<String, String> uiKeys = new ConcurrentHashMap<>();

    public ProviderKeyStore(ProvidersConfig providersConfig) {
        this.providersConfig = providersConfig;
    }

    /** The key to use for this provider, UI first, falling back to its configured env var. */
    public Optional<String> resolve(String providerId) {
        String uiKey = uiKeys.get(providerId);
        if (uiKey != null && !uiKey.isBlank()) {
            return Optional.of(uiKey);
        }
        return envKey(providerId);
    }

    public boolean isConfigured(String providerId) {
        return resolve(providerId).isPresent();
    }

    public KeySource sourceFor(String providerId) {
        if (uiKeys.containsKey(providerId)) {
            return KeySource.UI;
        }
        return envKey(providerId).isPresent() ? KeySource.ENV : KeySource.NONE;
    }

    /** First 4 and last 3 characters, never the full key. Empty/absent keys mask to null. */
    public Optional<String> maskedKey(String providerId) {
        return resolve(providerId).map(ProviderKeyStore::mask);
    }

    public void putUiKey(String providerId, String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key must not be blank");
        }
        uiKeys.put(providerId, apiKey.trim());
    }

    public void clearUiKey(String providerId) {
        uiKeys.remove(providerId);
    }

    static String mask(String key) {
        String trimmed = key.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "…" + trimmed.substring(trimmed.length() - 3);
    }

    private Optional<String> envKey(String providerId) {
        Optional<String> primary = providersConfig.getProviderEntry(providerId)
                .map(entry -> entry.apiKeyEnv)
                .filter(name -> name != null && !name.isBlank())
                .map(System::getenv)
                .filter(value -> value != null && !value.isBlank());
        if (primary.isPresent()) {
            return primary;
        }
        // config/providers.yaml documents GEMINI_API_KEY falling back to GOOGLE_API_KEY.
        if ("google".equals(providerId)) {
            String fallback = System.getenv("GOOGLE_API_KEY");
            if (fallback != null && !fallback.isBlank()) {
                return Optional.of(fallback);
            }
        }
        return Optional.empty();
    }
}
