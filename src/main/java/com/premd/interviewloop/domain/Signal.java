package com.premd.interviewloop.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/**
 * A rubric signal emitted during a round via the LLM's tool-use calls.
 * Signals accrue incrementally; the end-of-round evaluation summarises them.
 */
@Entity
@Table(name = "signal")
public class Signal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // LAZY back-references, never eagerly fetched by the queries that list signals for a
    // round — the caller already has the round id from the URL. Same problem and fix as
    // TranscriptTurn.round.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "round_id", nullable = false)
    @JsonIgnore
    private SessionRound round;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "turn_id")
    @JsonIgnore
    private TranscriptTurn turn;

    @Column(name = "rubric_dimension", nullable = false, length = 64)
    private String rubricDimension;

    @Column(nullable = false)
    private int score;  // 1–5

    @Column(length = 20)
    private String confidence;

    @Lob
    private String evidence;

    // -- Constructors --

    protected Signal() {}

    public Signal(SessionRound round, String rubricDimension, int score) {
        this.round = round;
        this.rubricDimension = rubricDimension;
        this.score = score;
    }

    // -- Getters / Setters --

    public Long getId() { return id; }

    public SessionRound getRound() { return round; }

    public TranscriptTurn getTurn() { return turn; }
    public void setTurn(TranscriptTurn turn) { this.turn = turn; }

    public String getRubricDimension() { return rubricDimension; }

    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }

    public String getConfidence() { return confidence; }
    public void setConfidence(String confidence) { this.confidence = confidence; }

    public String getEvidence() { return evidence; }
    public void setEvidence(String evidence) { this.evidence = evidence; }
}
