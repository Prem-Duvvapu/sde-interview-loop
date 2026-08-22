package com.premd.interviewloop.llm;

import com.premd.interviewloop.domain.LlmCall;
import com.premd.interviewloop.domain.SessionRound;
import com.premd.interviewloop.domain.TranscriptTurn;
import com.premd.interviewloop.domain.repository.LlmCallRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Persists LLM call records after every provider call.
 * Cost is a first-class concern — this exists from Phase 1, not as an afterthought.
 */
@Component
public class CostLedger {

    private static final Logger log = LoggerFactory.getLogger(CostLedger.class);

    private final LlmCallRepository repository;
    private final ProvidersConfig providersConfig;

    public CostLedger(LlmCallRepository repository, ProvidersConfig providersConfig) {
        this.repository = repository;
        this.providersConfig = providersConfig;
    }

    /**
     * Record an LLM call with usage stats. Calculates cost estimate from provider pricing.
     */
    public LlmCall record(String provider, String model, String role,
                          LlmEvent.Usage usage, int latencyMs,
                          SessionRound round, TranscriptTurn turn) {

        LlmCall call = new LlmCall(provider, model, role);
        call.setRound(round);
        call.setTurn(turn);
        call.setLatencyMs(latencyMs);

        if (usage != null) {
            call.setInputTokens(usage.inputTokens());
            call.setOutputTokens(usage.outputTokens());
            call.setCacheReadTokens(usage.cacheReadTokens());
            call.setCacheWriteTokens(usage.cacheWriteTokens());
            call.setCostEstimateUsd(estimateCost(provider, usage));
        }

        LlmCall saved = repository.save(call);

        log.info("LLM call [{}:{}] role={} in={}  out={} cache_read={} cost=${} latency={}ms",
                provider, model, role,
                usage != null ? usage.inputTokens() : 0,
                usage != null ? usage.outputTokens() : 0,
                usage != null ? usage.cacheReadTokens() : 0,
                saved.getCostEstimateUsd() != null ? String.format("%.4f", saved.getCostEstimateUsd()) : "?",
                latencyMs);

        return saved;
    }

    private double estimateCost(String providerId, LlmEvent.Usage usage) {
        return providersConfig.getProviderEntry(providerId)
                .filter(p -> p.pricing != null)
                .map(p -> {
                    double inputPrice = p.pricing.getInputPrice();
                    double outputPrice = p.pricing.getOutputPrice();
                    // Pricing is per million tokens
                    double inputCost = (usage.inputTokens() / 1_000_000.0) * inputPrice;
                    double outputCost = (usage.outputTokens() / 1_000_000.0) * outputPrice;
                    return inputCost + outputCost;
                })
                .orElse(0.0);
    }
}
