package com.premd.interviewloop.domain;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.premd.interviewloop.domain.enums.SessionMode;
import com.premd.interviewloop.domain.enums.SessionStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "interview_session")
public class InterviewSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionMode mode;

    @Column(name = "company_profile_id", nullable = false, length = 64)
    private String companyProfileId;

    @Column(name = "profile_content_hash", length = 64)
    private String profileContentHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SessionStatus status = SessionStatus.ACTIVE;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "ended_at")
    private Instant endedAt;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("ordinal ASC")
    @JsonManagedReference
    private List<SessionRound> rounds = new ArrayList<>();

    // -- Constructors --

    protected InterviewSession() {}

    public InterviewSession(SessionMode mode, String companyProfileId) {
        this.mode = mode;
        this.companyProfileId = companyProfileId;
    }

    // -- Getters / Setters --

    public Long getId() { return id; }

    public SessionMode getMode() { return mode; }
    public void setMode(SessionMode mode) { this.mode = mode; }

    public String getCompanyProfileId() { return companyProfileId; }
    public void setCompanyProfileId(String companyProfileId) { this.companyProfileId = companyProfileId; }

    public String getProfileContentHash() { return profileContentHash; }
    public void setProfileContentHash(String profileContentHash) { this.profileContentHash = profileContentHash; }

    public SessionStatus getStatus() { return status; }
    public void setStatus(SessionStatus status) { this.status = status; }

    public Instant getStartedAt() { return startedAt; }

    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }

    public List<SessionRound> getRounds() { return rounds; }

    public void addRound(SessionRound round) {
        rounds.add(round);
        round.setSession(this);
    }
}
