package com.premd.interviewloop.llm;

import reactor.core.publisher.Flux;

/**
 * Service Provider Interface for LLM backends.
 * Each provider adapter (Gemini, Claude, OpenAI, etc.) implements this interface.
 *
 * The SPI is frozen after Phase 1 — adding a provider is additive,
 * changing this interface requires coordinating all adapters.
 */
public interface LlmProvider {

    /** Unique provider id, e.g. "google", "anthropic", "openai". */
    String id();

    /** Human-readable name for UI display. */
    String displayName();

    /** Declared capabilities of this provider. */
    Capabilities capabilities();

    /**
     * Stream a response from the LLM. Returns a Flux of events:
     * TEXT_DELTA, TOOL_CALL, USAGE, DONE, ERROR.
     *
     * The caller assembles the request in cache-stable order (§1.4);
     * the adapter is responsible for mapping it to the provider's wire format
     * and for provider-specific caching mechanics.
     */
    Flux<LlmEvent> stream(LlmRequest request);
}
