package com.premd.interviewloop.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

/**
 * Post-round evaluation: aggregated rubric scores, strengths, gaps, and a readiness band.
 * Records which evaluator model produced the scores for comparability tracking.
 */
@Entity
@Table(name = "round_evaluation")
public class RoundEvaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // LAZY, never eagerly fetched by whatever eventually lists evaluations for a round — the
    // caller already has the round id from the URL. Same problem and fix as TranscriptTurn.round.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "round_id", nullable = false)
    @JsonIgnore
    private SessionRound round;

    @Column(name = "rubric_version", length = 20)
    private String rubricVersion;

    @Column(name = "evaluator_provider", length = 30)
    private String evaluatorProvider;

    @Column(name = "evaluator_model", length = 64)
    private String evaluatorModel;

    @Column(name = "comparability_epoch", nullable = false)
    private int comparabilityEpoch = 1;

    @Lob
    private String scores;  // JSON: dimension -> score

    @Lob
    private String strengths;

    @Lob
    private String gaps;

    @Column(name = "readiness_band", length = 20)
    private String readinessBand;

    @Lob
    @Column(name = "narrative_md")
    private String narrativeMd;

    // -- Constructors --

    protected RoundEvaluation() {}

    public RoundEvaluation(SessionRound round) {
        this.round = round;
    }

    // -- Getters / Setters --

    public Long getId() { return id; }

    public SessionRound getRound() { return round; }

    public String getRubricVersion() { return rubricVersion; }
    public void setRubricVersion(String rubricVersion) { this.rubricVersion = rubricVersion; }

    public String getEvaluatorProvider() { return evaluatorProvider; }
    public void setEvaluatorProvider(String evaluatorProvider) { this.evaluatorProvider = evaluatorProvider; }

    public String getEvaluatorModel() { return evaluatorModel; }
    public void setEvaluatorModel(String evaluatorModel) { this.evaluatorModel = evaluatorModel; }

    public int getComparabilityEpoch() { return comparabilityEpoch; }
    public void setComparabilityEpoch(int comparabilityEpoch) { this.comparabilityEpoch = comparabilityEpoch; }

    public String getScores() { return scores; }
    public void setScores(String scores) { this.scores = scores; }

    public String getStrengths() { return strengths; }
    public void setStrengths(String strengths) { this.strengths = strengths; }

    public String getGaps() { return gaps; }
    public void setGaps(String gaps) { this.gaps = gaps; }

    public String getReadinessBand() { return readinessBand; }
    public void setReadinessBand(String readinessBand) { this.readinessBand = readinessBand; }

    public String getNarrativeMd() { return narrativeMd; }
    public void setNarrativeMd(String narrativeMd) { this.narrativeMd = narrativeMd; }
}
