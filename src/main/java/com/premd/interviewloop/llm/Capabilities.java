package com.premd.interviewloop.llm;

/**
 * Declared capabilities of an LLM provider.
 * Used for capability-floor enforcement at the SPI boundary.
 */
public record Capabilities(
        boolean streaming,
        boolean toolUse,
        PromptCachingMode promptCaching,
        boolean vision
) {

    public enum PromptCachingMode {
        /** No caching support. */
        NONE,
        /** Provider manages caching automatically (e.g. OpenAI prefix cache). */
        AUTOMATIC,
        /** Requires explicit cache control (e.g. Anthropic breakpoints, Gemini TTL objects). */
        EXPLICIT
    }

    /** Check whether the minimum capability floor for interviewer duty is met. */
    public boolean meetsInterviewerFloor() {
        return streaming && toolUse;
    }

    /** Check whether the minimum capability floor for evaluator duty is met. */
    public boolean meetsEvaluatorFloor() {
        return toolUse;
    }
}
