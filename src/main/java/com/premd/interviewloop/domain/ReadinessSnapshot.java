package com.premd.interviewloop.domain;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Denormalised readiness snapshot for the dashboard.
 */
@Entity
@Table(name = "readiness_snapshot")
public class ReadinessSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "taken_at", nullable = false)
    private Instant takenAt = Instant.now();

    @Column(name = "module_type", nullable = false, length = 30)
    private String moduleType;

    @Column(name = "company_profile_id", nullable = false, length = 64)
    private String companyProfileId;

    @Column(nullable = false)
    private double score;

    @Column(name = "sample_size", nullable = false)
    private int sampleSize;

    // -- Constructors --

    protected ReadinessSnapshot() {}

    public ReadinessSnapshot(String moduleType, String companyProfileId, double score, int sampleSize) {
        this.moduleType = moduleType;
        this.companyProfileId = companyProfileId;
        this.score = score;
        this.sampleSize = sampleSize;
    }

    // -- Getters --

    public Long getId() { return id; }
    public Instant getTakenAt() { return takenAt; }
    public String getModuleType() { return moduleType; }
    public String getCompanyProfileId() { return companyProfileId; }
    public double getScore() { return score; }
    public int getSampleSize() { return sampleSize; }
}
