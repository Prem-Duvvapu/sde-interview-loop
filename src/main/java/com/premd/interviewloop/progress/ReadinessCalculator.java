package com.premd.interviewloop.progress;

import com.premd.interviewloop.domain.ReadinessSnapshot;
import com.premd.interviewloop.domain.enums.ReadinessBand;
import com.premd.interviewloop.domain.repository.ReadinessSnapshotRepository;
import com.premd.interviewloop.profile.CompanyProfile;
import com.premd.interviewloop.profile.ProfileLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes readiness from historical snapshots.
 *
 * <p>Readiness per PROJECT_PLAN.md §3:
 * <ul>
 *   <li>{@code emphasis}-weighted mean across module scores</li>
 *   <li>Recency weighting — older sessions decay</li>
 *   <li>Gated by {@code readiness.module_minimums} — one module below its floor blocks "ready"</li>
 *   <li>Confidence driven by {@code min_sessions_for_confidence}</li>
 * </ul>
 */
@Service
public class ReadinessCalculator {

    private static final Logger log = LoggerFactory.getLogger(ReadinessCalculator.class);

    /**
     * Half-life for recency decay in days. A session 30 days old contributes half the weight
     * of a session today.
     */
    static final double DECAY_HALF_LIFE_DAYS = 30.0;

    private final ReadinessSnapshotRepository snapshotRepo;
    private final ProfileLoader profileLoader;

    public ReadinessCalculator(ReadinessSnapshotRepository snapshotRepo,
                               ProfileLoader profileLoader) {
        this.snapshotRepo = snapshotRepo;
        this.profileLoader = profileLoader;
    }

    /**
     * Record a readiness snapshot after a round evaluation completes.
     */
    public void recordSnapshot(String moduleType, String companyProfileId, double score, int comparabilityEpoch) {
        // Count existing snapshots for sample size
        List<ReadinessSnapshot> existing = snapshotRepo
                .findByCompanyProfileIdAndModuleTypeOrderByTakenAtDesc(companyProfileId, moduleType).stream()
                .filter(snapshot -> snapshot.getComparabilityEpoch() == comparabilityEpoch)
                .toList();
        int sampleSize = existing.size() + 1;
        ReadinessSnapshot snapshot = new ReadinessSnapshot(
                moduleType, companyProfileId, score, sampleSize, comparabilityEpoch);
        snapshotRepo.save(snapshot);
        log.info("Recorded readiness snapshot: module={} company={} epoch={} score={} sample={}",
                moduleType, companyProfileId, comparabilityEpoch, score, sampleSize);
    }

    /**
     * Compute current readiness for a company.
     */
    public ReadinessResult computeReadiness(String companyProfileId) {
        return computeReadiness(companyProfileId, 1);
    }

    /**
     * Compute readiness within one evaluator epoch. Scores from prior evaluator bindings are
     * retained for historical display but are not a compatible input to the current readiness.
     */
    public ReadinessResult computeReadiness(String companyProfileId, int comparabilityEpoch) {
        CompanyProfile profile;
        try {
            profile = profileLoader.getProfile(companyProfileId);
        } catch (Exception e) {
            return ReadinessResult.unavailable("Profile not found: " + companyProfileId);
        }

        Map<String, Double> emphasis = profile.getEmphasis();
        if (emphasis == null || emphasis.isEmpty()) {
            return ReadinessResult.unavailable("Profile has no emphasis weights");
        }

        Instant now = Instant.now();
        Map<String, Double> moduleScores = new LinkedHashMap<>();
        Map<String, Integer> moduleSampleCounts = new LinkedHashMap<>();
        int totalSamples = 0;

        for (String moduleType : emphasis.keySet()) {
            List<ReadinessSnapshot> snapshots = snapshotRepo
                    .findByCompanyProfileIdAndModuleTypeOrderByTakenAtDesc(companyProfileId, moduleType).stream()
                    .filter(snapshot -> snapshot.getComparabilityEpoch() == comparabilityEpoch)
                    .toList();
            if (snapshots.isEmpty()) {
                continue;
            }

            double weightedSum = 0;
            double totalWeight = 0;
            for (ReadinessSnapshot s : snapshots) {
                double weight = decayWeight(s.getTakenAt(), now);
                weightedSum += s.getScore() * weight;
                totalWeight += weight;
            }

            double recencyWeightedScore = totalWeight > 0 ? weightedSum / totalWeight : 0;
            moduleScores.put(moduleType, recencyWeightedScore);
            moduleSampleCounts.put(moduleType, snapshots.size());
            totalSamples += snapshots.size();
        }

        if (moduleScores.isEmpty()) {
            return ReadinessResult.unavailable("No snapshots recorded yet");
        }

        // Emphasis-weighted overall score
        double weightedSum = 0;
        double totalWeight = 0;
        for (Map.Entry<String, Double> e : moduleScores.entrySet()) {
            double weight = emphasis.getOrDefault(e.getKey(), 0.0);
            if (weight > 0) {
                weightedSum += e.getValue() * weight;
                totalWeight += weight;
            }
        }
        double overallScore = totalWeight > 0 ? weightedSum / totalWeight : 0;

        // Module minimum gating
        Map<String, Double> failingMinimums = new LinkedHashMap<>();
        CompanyProfile.Readiness readinessConfig = profile.getReadiness();
        boolean gated = false;
        if (readinessConfig != null && readinessConfig.getModuleMinimums() != null) {
            for (Map.Entry<String, Double> min : readinessConfig.getModuleMinimums().entrySet()) {
                Double actual = moduleScores.get(min.getKey());
                if (actual == null || actual < min.getValue()) {
                    failingMinimums.put(min.getKey(), min.getValue());
                    gated = true;
                }
            }
        }

        // Compute band, but cap it if gated
        ReadinessBand band = ReadinessBand.fromScore(overallScore);
        if (gated && band.ordinal() > ReadinessBand.LEAN_HIRE.ordinal()) {
            band = ReadinessBand.LEAN_HIRE;
        }

        // Confidence
        int minForConfidence = (readinessConfig != null && readinessConfig.getMinSessionsForConfidence() != null)
                ? readinessConfig.getMinSessionsForConfidence() : 3;
        String confidence = totalSamples >= minForConfidence ? "confident" : "low";

        return new ReadinessResult(
                band.wireValue(),
                overallScore,
                moduleScores,
                moduleSampleCounts,
                failingMinimums,
                confidence,
                totalSamples,
                null);
    }

    /**
     * Exponential decay weight based on age. Score from today has weight 1.0; a score
     * {@link #DECAY_HALF_LIFE_DAYS} days ago has weight 0.5.
     */
    static double decayWeight(Instant takenAt, Instant now) {
        double ageDays = Duration.between(takenAt, now).toHours() / 24.0;
        if (ageDays <= 0) return 1.0;
        return Math.pow(0.5, ageDays / DECAY_HALF_LIFE_DAYS);
    }

    /**
     * Result of a readiness computation.
     */
    public record ReadinessResult(
            String band,
            double overallScore,
            Map<String, Double> moduleScores,
            Map<String, Integer> moduleSampleCounts,
            Map<String, Double> failingMinimums,
            String confidence,
            int totalSamples,
            String error
    ) {
        static ReadinessResult unavailable(String error) {
            return new ReadinessResult(null, 0, Map.of(), Map.of(), Map.of(), "none", 0, error);
        }
    }
}
