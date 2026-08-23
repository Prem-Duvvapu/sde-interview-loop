package com.premd.interviewloop.llm;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Where a provider's API key came from. Surfaced to the UI so a key pasted through
 * Settings is visibly distinct from one already present in the environment — and so a
 * UI-supplied key can be identified as the one that wins when both are present.
 */
public enum KeySource {
    UI, ENV, NONE;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
