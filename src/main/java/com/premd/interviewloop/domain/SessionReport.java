package com.premd.interviewloop.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/**
 * Aggregated report for an entire session (all rounds).
 */
@Entity
@Table(name = "session_report")
public class SessionReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // LAZY, never eagerly fetched by whatever eventually looks up a report for a session — the
    // caller already has the session id from the URL. Same problem and fix as TranscriptTurn.round.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    @JsonIgnore
    private InterviewSession session;

    @Column(name = "overall_band", length = 20)
    private String overallBand;

    @Lob
    @Column(name = "per_module")
    private String perModule;  // JSON: per-module scores

    @Lob
    @Column(name = "narrative_md")
    private String narrativeMd;

    // -- Constructors --

    protected SessionReport() {}

    public SessionReport(InterviewSession session) {
        this.session = session;
    }

    // -- Getters / Setters --

    public Long getId() { return id; }

    public InterviewSession getSession() { return session; }

    public String getOverallBand() { return overallBand; }
    public void setOverallBand(String overallBand) { this.overallBand = overallBand; }

    public String getPerModule() { return perModule; }
    public void setPerModule(String perModule) { this.perModule = perModule; }

    public String getNarrativeMd() { return narrativeMd; }
    public void setNarrativeMd(String narrativeMd) { this.narrativeMd = narrativeMd; }
}
