package com.premd.interviewloop.llm;

import java.util.List;

/**
 * Everything the UI needs to render one provider card, sourced from config/providers.yaml
 * metadata plus live key-store state — never requires building an actual client, so it
 * can describe a provider the owner hasn't added a key for yet.
 */
public record ProviderInfo(
        String id,
        String displayName,
        boolean enabled,
        boolean configured,
        KeySource keySource,
        String maskedKey,
        Capabilities capabilities,
        List<ModelInfo> models,
        boolean meetsInterviewerFloor,
        boolean meetsEvaluatorFloor
) {
    public record ModelInfo(String id, String roleHint, Integer contextTokens, String notes) {}
}
