package com.premd.interviewloop.domain;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.domain.enums.RoundPhase;
import com.premd.interviewloop.domain.enums.RoundStatus;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "session_round")
public class SessionRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonBackReference

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private InterviewSession session;

    @Column(nullable = false)
    private int ordinal;

    @Enumerated(EnumType.STRING)
    @Column(name = "module_type", nullable = false, length = 30)
    private ModuleType moduleType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RoundPhase phase = RoundPhase.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RoundStatus status = RoundStatus.PENDING;

    @Column(name = "interviewer_provider", length = 30)
    private String interviewerProvider;

    @Column(name = "interviewer_model", length = 64)
    private String interviewerModel;

    @Column(name = "question_slug", length = 128)
    private String questionSlug;

    @Column(name = "question_content_hash", length = 64)
    private String questionContentHash;

    @Column(name = "difficulty_target", length = 20)
    private String difficultyTarget;

    @Column(name = "planned_duration_sec")
    private Integer plannedDurationSec;

    @Column(name = "actual_duration_sec")
    private Integer actualDurationSec;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    // -- Constructors --

    protected SessionRound() {}

    public SessionRound(int ordinal, ModuleType moduleType) {
        this.ordinal = ordinal;
        this.moduleType = moduleType;
    }

    // -- Getters / Setters --

    public Long getId() { return id; }

    public InterviewSession getSession() { return session; }
    public void setSession(InterviewSession session) { this.session = session; }

    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int ordinal) { this.ordinal = ordinal; }

    public ModuleType getModuleType() { return moduleType; }
    public void setModuleType(ModuleType moduleType) { this.moduleType = moduleType; }

    public RoundPhase getPhase() { return phase; }
    public void setPhase(RoundPhase phase) { this.phase = phase; }

    public RoundStatus getStatus() { return status; }
    public void setStatus(RoundStatus status) { this.status = status; }

    public String getInterviewerProvider() { return interviewerProvider; }
    public void setInterviewerProvider(String interviewerProvider) { this.interviewerProvider = interviewerProvider; }

    public String getInterviewerModel() { return interviewerModel; }
    public void setInterviewerModel(String interviewerModel) { this.interviewerModel = interviewerModel; }

    public String getQuestionSlug() { return questionSlug; }
    public void setQuestionSlug(String questionSlug) { this.questionSlug = questionSlug; }

    public String getQuestionContentHash() { return questionContentHash; }
    public void setQuestionContentHash(String questionContentHash) { this.questionContentHash = questionContentHash; }

    public String getDifficultyTarget() { return difficultyTarget; }
    public void setDifficultyTarget(String difficultyTarget) { this.difficultyTarget = difficultyTarget; }

    public Integer getPlannedDurationSec() { return plannedDurationSec; }
    public void setPlannedDurationSec(Integer plannedDurationSec) { this.plannedDurationSec = plannedDurationSec; }

    public Integer getActualDurationSec() { return actualDurationSec; }
    public void setActualDurationSec(Integer actualDurationSec) { this.actualDurationSec = actualDurationSec; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
}
