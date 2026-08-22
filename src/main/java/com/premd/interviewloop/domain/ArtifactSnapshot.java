package com.premd.interviewloop.domain;

import com.premd.interviewloop.domain.enums.ArtifactKind;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Append-only snapshot of an artifact (code, diagram, etc.) at a point in time.
 * Enables scrubbable replay of the interview.
 */
@Entity
@Table(name = "artifact_snapshot")
public class ArtifactSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "round_id", nullable = false)
    private SessionRound round;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "turn_id")
    private TranscriptTurn turn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ArtifactKind kind;

    @Column(length = 30)
    private String language;

    @Lob
    @Column(nullable = false)
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // -- Constructors --

    protected ArtifactSnapshot() {}

    public ArtifactSnapshot(SessionRound round, ArtifactKind kind, String payload) {
        this.round = round;
        this.kind = kind;
        this.payload = payload;
    }

    // -- Getters / Setters --

    public Long getId() { return id; }

    public SessionRound getRound() { return round; }

    public TranscriptTurn getTurn() { return turn; }
    public void setTurn(TranscriptTurn turn) { this.turn = turn; }

    public ArtifactKind getKind() { return kind; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public String getPayload() { return payload; }

    public Instant getCreatedAt() { return createdAt; }
}
