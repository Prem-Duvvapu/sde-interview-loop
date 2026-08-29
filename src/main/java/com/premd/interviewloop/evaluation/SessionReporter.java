package com.premd.interviewloop.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premd.interviewloop.domain.InterviewSession;
import com.premd.interviewloop.domain.RoundEvaluation;
import com.premd.interviewloop.domain.SessionReport;
import com.premd.interviewloop.domain.SessionRound;
import com.premd.interviewloop.domain.enums.ReadinessBand;
import com.premd.interviewloop.domain.enums.RoundStatus;
import com.premd.interviewloop.domain.repository.RoundEvaluationRepository;
import com.premd.interviewloop.domain.repository.SessionReportRepository;
import com.premd.interviewloop.domain.repository.SessionRoundRepository;
import com.premd.interviewloop.domain.repository.InterviewSessionRepository;
import com.premd.interviewloop.profile.CompanyProfile;
import com.premd.interviewloop.profile.ProfileLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generates a session-level report by aggregating per-round evaluations.
 *
 * <p>Called after a session auto-completes (all rounds done). Best-effort: a failure here
 * must never undo session completion — callers wrap this in try/catch and log a warning.
 *
 * <p>For a {@code single_module} session this is a thin wrapper over one round's evaluation.
 * For a {@code full_loop} session it is the combined view the project exists to produce.
 */
@Service
public class SessionReporter {

    private static final Logger log = LoggerFactory.getLogger(SessionReporter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final InterviewSessionRepository sessionRepo;
    private final SessionRoundRepository roundRepo;
    private final RoundEvaluationRepository evaluationRepo;
    private final SessionReportRepository reportRepo;
    private final ProfileLoader profileLoader;

    public SessionReporter(InterviewSessionRepository sessionRepo,
                           SessionRoundRepository roundRepo,
                           RoundEvaluationRepository evaluationRepo,
                           SessionReportRepository reportRepo,
                           ProfileLoader profileLoader) {
        this.sessionRepo = sessionRepo;
        this.roundRepo = roundRepo;
        this.evaluationRepo = evaluationRepo;
        this.reportRepo = reportRepo;
        this.profileLoader = profileLoader;
    }

    /**
     * Generate (or return existing) report for a completed session.
     *
     * @return the persisted report, or empty if the session has no evaluated rounds
     */
    public Optional<SessionReport> report(Long sessionId) {
        // Guard against duplicate creation
        Optional<SessionReport> existing = reportRepo.findBySessionId(sessionId);
        if (existing.isPresent()) {
            log.info("Session {}: report already exists, returning existing", sessionId);
            return existing;
        }

        InterviewSession session = sessionRepo.findById(sessionId).orElse(null);
        if (session == null) {
            log.warn("Session {} not found, cannot generate report", sessionId);
            return Optional.empty();
        }

        // Load rounds separately by session id (LAZY, open-in-view disabled — invariant 6)
        List<SessionRound> rounds = roundRepo.findBySessionIdOrderByOrdinalAsc(sessionId);

        // Collect evaluated rounds: skip rounds with no evaluation
        Map<String, Double> perModuleScores = new LinkedHashMap<>();
        StringBuilder narrativeBuilder = new StringBuilder();
        int evaluatedCount = 0;

        for (SessionRound round : rounds) {
            if (round.getStatus() == RoundStatus.SKIPPED) {
                continue;
            }
            Optional<RoundEvaluation> evalOpt = evaluationRepo.findByRoundId(round.getId());
            if (evalOpt.isEmpty()) {
                continue;
            }
            RoundEvaluation eval = evalOpt.get();
            evaluatedCount++;

            // Parse scores JSON and compute mean for this round
            Map<String, Integer> scores = readJson(eval.getScores(),
                    new TypeReference<Map<String, Integer>>() {}, Map.of());
            double roundMean = scores.values().stream().mapToInt(Integer::intValue).average().orElse(0);

            String moduleKey = round.getModuleType().getValue();
            perModuleScores.put(moduleKey, roundMean);

            // Accumulate narrative
            if (eval.getNarrativeMd() != null && !eval.getNarrativeMd().isBlank()) {
                if (!narrativeBuilder.isEmpty()) {
                    narrativeBuilder.append("\n\n---\n\n");
                }
                narrativeBuilder.append("### ").append(round.getModuleType().getValue().toUpperCase())
                        .append(" (Round ").append(round.getOrdinal()).append(")\n\n")
                        .append(eval.getNarrativeMd());
            }
        }

        if (evaluatedCount == 0) {
            log.warn("Session {}: no evaluated rounds, skipping report", sessionId);
            return Optional.empty();
        }

        // Compute overall band: emphasis-weighted mean if profile has emphasis, else simple mean
        double overallScore = computeOverallScore(session.getCompanyProfileId(), perModuleScores);
        ReadinessBand band = ReadinessBand.fromScore(overallScore);

        SessionReport report = new SessionReport(session);
        report.setOverallBand(band.wireValue());
        report.setPerModule(toJson(perModuleScores));
        report.setNarrativeMd(narrativeBuilder.toString());

        SessionReport saved = reportRepo.save(report);
        log.info("Session {} report: band={} modules={}", sessionId, band.wireValue(), perModuleScores.keySet());
        return Optional.of(saved);
    }

    /**
     * Compute emphasis-weighted mean across module scores, falling back to simple mean
     * when the profile has no emphasis map or the emphasis map does not cover all modules.
     */
    private double computeOverallScore(String companyProfileId, Map<String, Double> perModuleScores) {
        if (perModuleScores.isEmpty()) {
            return 0;
        }

        try {
            CompanyProfile profile = profileLoader.getProfile(companyProfileId);
            Map<String, Double> emphasis = profile.getEmphasis();
            if (emphasis != null && !emphasis.isEmpty()) {
                double weightedSum = 0;
                double totalWeight = 0;
                for (Map.Entry<String, Double> entry : perModuleScores.entrySet()) {
                    double weight = emphasis.getOrDefault(entry.getKey(), 0.0);
                    if (weight > 0) {
                        weightedSum += entry.getValue() * weight;
                        totalWeight += weight;
                    }
                }
                if (totalWeight > 0) {
                    return weightedSum / totalWeight;
                }
            }
        } catch (Exception e) {
            log.warn("Could not load profile '{}' for emphasis weighting, using simple mean: {}",
                    companyProfileId, e.getMessage());
        }

        // Fall back to simple mean
        return perModuleScores.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private <T> T readJson(String raw, TypeReference<T> type, T fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return JSON.readValue(raw, type);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise session report field", e);
        }
    }
}
