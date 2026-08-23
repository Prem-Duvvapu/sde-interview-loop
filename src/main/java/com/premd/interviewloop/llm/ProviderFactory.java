package com.premd.interviewloop.llm;

/**
 * Builds a live {@link LlmProvider} for a vendor, given a key.
 *
 * One factory bean per implemented vendor, always registered regardless of whether a key
 * is currently available — that is what lets the catalog list a provider as "add a key to
 * use it" instead of it being invisible. The factory itself is stateless; it never sees
 * anything other than the key handed to it for one call, and never stores it.
 */
public interface ProviderFactory {

    /** Must match the provider {@code id} in config/providers.yaml. */
    String id();

    /** Build a client bound to this key. Cheap: no network call, safe to call per-request. */
    LlmProvider create(String apiKey);
}
