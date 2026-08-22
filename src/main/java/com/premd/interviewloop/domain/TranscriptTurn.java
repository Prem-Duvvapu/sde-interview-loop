package com.premd.interviewloop.domain;

import com.premd.interviewloop.domain.enums.TurnRole;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * A single turn in the transcript. Append-only — edits are never permitted.
 */
@Entity
@Table(name = "transcript_turn")
public class TranscriptTurn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "round_id", nullable = false)
    private SessionRound round;

    @Column(nullable = false)
    private int ordinal;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TurnRole role;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType = "text";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "latency_ms")
    private Integer latencyMs;

    // -- Constructors --

    protected TranscriptTurn() {}

    public TranscriptTurn(SessionRound round, int ordinal, TurnRole role, String content) {
        this.round = round;
        this.ordinal = ordinal;
        this.role = role;
        this.content = content;
    }

    // -- Getters / Setters --

    public Long getId() { return id; }

    public SessionRound getRound() { return round; }
    public void setRound(SessionRound round) { this.round = round; }

    public int getOrdinal() { return ordinal; }

    public TurnRole getRole() { return role; }

    public String getContent() { return content; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public Instant getCreatedAt() { return createdAt; }

    public Integer getLatencyMs() { return latencyMs; }
    public void setLatencyMs(Integer latencyMs) { this.latencyMs = latencyMs; }
}
