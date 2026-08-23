package com.premd.interviewloop.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Per-call LLM observability record: tokens, cost, latency, per provider.
 * Exists from Phase 1 — cost is a first-class concern.
 */
@Entity
@Table(name = "llm_call")
public class LlmCall {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // LAZY back-references, never eagerly fetched by whatever eventually lists llm_calls for a
    // round — the caller already has the round id from the URL. Same problem and fix as
    // TranscriptTurn.round.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "round_id")
    @JsonIgnore
    private SessionRound round;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "turn_id")
    @JsonIgnore
    private TranscriptTurn turn;

    @Column(nullable = false, length = 30)
    private String provider;

    @Column(nullable = false, length = 64)
    private String model;

    @Column(nullable = false, length = 20)
    private String role;  // interviewer | evaluator | summariser

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "cache_read_tokens")
    private Integer cacheReadTokens;

    @Column(name = "cache_write_tokens")
    private Integer cacheWriteTokens;

    @Column(name = "cost_estimate_usd")
    private Double costEstimateUsd;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // -- Constructors --

    protected LlmCall() {}

    public LlmCall(String provider, String model, String role) {
        this.provider = provider;
        this.model = model;
        this.role = role;
    }

    // -- Getters / Setters --

    public Long getId() { return id; }

    public SessionRound getRound() { return round; }
    public void setRound(SessionRound round) { this.round = round; }

    public TranscriptTurn getTurn() { return turn; }
    public void setTurn(TranscriptTurn turn) { this.turn = turn; }

    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getRole() { return role; }

    public Integer getInputTokens() { return inputTokens; }
    public void setInputTokens(Integer inputTokens) { this.inputTokens = inputTokens; }

    public Integer getOutputTokens() { return outputTokens; }
    public void setOutputTokens(Integer outputTokens) { this.outputTokens = outputTokens; }

    public Integer getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(Integer cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }

    public Integer getCacheWriteTokens() { return cacheWriteTokens; }
    public void setCacheWriteTokens(Integer cacheWriteTokens) { this.cacheWriteTokens = cacheWriteTokens; }

    public Double getCostEstimateUsd() { return costEstimateUsd; }
    public void setCostEstimateUsd(Double costEstimateUsd) { this.costEstimateUsd = costEstimateUsd; }

    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }

    public Instant getCreatedAt() { return createdAt; }
}
