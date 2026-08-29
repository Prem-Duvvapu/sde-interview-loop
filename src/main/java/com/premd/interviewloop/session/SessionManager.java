package com.premd.interviewloop.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.premd.interviewloop.domain.InterviewSession;
import com.premd.interviewloop.domain.RoundEvaluation;
import com.premd.interviewloop.domain.SessionRound;
import com.premd.interviewloop.domain.enums.*;
import com.premd.interviewloop.domain.repository.InterviewSessionRepository;
import com.premd.interviewloop.domain.repository.SessionRoundRepository;
import com.premd.interviewloop.profile.CompanyProfile;
import com.premd.interviewloop.profile.ProfileLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Manages interview session lifecycle: creation, round management, and completion.
 */
@Service
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final InterviewSessionRepository sessionRepo;
    private final SessionRoundRepository roundRepo;
    private final ProfileLoader profileLoader;
    private final SessionStateMachine stateMachine;
    private final ObjectMapper objectMapper;

    public SessionManager(InterviewSessionRepository sessionRepo,
                          SessionRoundRepository roundRepo,
                          ProfileLoader profileLoader,
                          SessionStateMachine stateMachine,
                          ObjectMapper objectMapper) {
        this.sessionRepo = sessionRepo;
        this.roundRepo = roundRepo;
        this.profileLoader = profileLoader;
        this.stateMachine = stateMachine;
        this.objectMapper = objectMapper;
    }

    /**
     * Create a new interview session.
     */
    @Transactional
    public InterviewSession createSession(String companyProfileId, SessionMode mode,
                                          String providerId, String modelId) {
        CompanyProfile profile = profileLoader.getProfile(companyProfileId);

        InterviewSession session = new InterviewSession(mode, companyProfileId);
        session.setProfileContentHash(profileLoader.getContentHash(companyProfileId));

        if (mode == SessionMode.FULL_LOOP) {
            // Create rounds for every round in the company profile
            for (CompanyProfile.Round profileRound : profile.getLoop().getRounds()) {
                SessionRound round = new SessionRound(
                        profileRound.getOrdinal(),
                        ModuleType.fromValue(profileRound.getModule())
                );
                round.setDifficultyTarget(profileRound.getDifficultyTarget());
                round.setPlannedDurationSec(profileRound.getDurationMin() * 60);
                round.setInterviewerProvider(providerId);
                round.setInterviewerModel(modelId);

                // Mark rounds disabled in v1 as SKIPPED
                if (!profileRound.isEnabledInV1()) {
                    round.setStatus(RoundStatus.SKIPPED);
                }

                session.addRound(round);
            }
        } else {
            // Single module: create one round
            SessionRound round = new SessionRound(1, ModuleType.DSA);  // Default; caller should specify
            round.setInterviewerProvider(providerId);
            round.setInterviewerModel(modelId);
            session.addRound(round);
        }

        session = sessionRepo.save(session);
        log.info("Created session {} ({} mode) for company '{}'",
                session.getId(), mode, companyProfileId);
        return session;
    }

    /**
     * Create a single-module practice session.
     */
    @Transactional
    public InterviewSession createSingleModuleSession(String companyProfileId,
                                                       ModuleType moduleType,
                                                       String difficultyTarget,
                                                       String providerId, String modelId) {
        InterviewSession session = new InterviewSession(SessionMode.SINGLE_MODULE, companyProfileId);
        session.setProfileContentHash(profileLoader.getContentHash(companyProfileId));

        SessionRound round = new SessionRound(1, moduleType);
        round.setDifficultyTarget(difficultyTarget);
        round.setInterviewerProvider(providerId);
        round.setInterviewerModel(modelId);
        session.addRound(round);

        session = sessionRepo.save(session);
        log.info("Created single-module session {} ({}) for company '{}'",
                session.getId(), moduleType, companyProfileId);
        return session;
    }

    /**
     * Start a round within a session.
     */
    @Transactional
    public SessionRound startRound(Long roundId) {
        SessionRound round = roundRepo.findById(roundId)
                .orElseThrow(() -> new NoSuchElementException("Round not found: " + roundId));

        // A full loop has one active interviewer at a time. Keeping this guard server-side means
        // a stale browser tab cannot skip ahead and invalidate the planned difficulty sequence.
        int roundOrdinal = round.getOrdinal();
        boolean earlierRoundOutstanding = roundRepo.findBySessionIdOrderByOrdinalAsc(round.getSession().getId())
                .stream()
                .anyMatch(candidate -> candidate.getOrdinal() < roundOrdinal
                        && candidate.getStatus() != RoundStatus.COMPLETED
                        && candidate.getStatus() != RoundStatus.SKIPPED);
        if (earlierRoundOutstanding) {
            throw new IllegalStateException("Complete the preceding round before starting this one");
        }

        stateMachine.validateRoundTransition(round, RoundStatus.IN_PROGRESS);

        round.setStatus(RoundStatus.IN_PROGRESS);
        round.setPhase(RoundPhase.BRIEFING);
        round.setStartedAt(Instant.now());

        round = roundRepo.save(round);
        log.info("Started round {} ({})", roundId, round.getModuleType());
        return round;
    }

    /**
     * Makes the next enabled round ready after an evaluation is available. The handoff is a small
     * private panel note, not a transcript: each interviewer still assesses their own round.
     * Evaluation failure deliberately does not strand a full loop; the next interviewer simply
     * receives no prior-round conclusions.
     */
    @Transactional
    public Optional<RoundAdvance> prepareNextRound(Long completedRoundId, RoundEvaluation evaluation) {
        SessionRound completed = roundRepo.findByIdWithSession(completedRoundId)
                .orElseThrow(() -> new NoSuchElementException("Round not found: " + completedRoundId));
        InterviewSession session = completed.getSession();
        if (session.getMode() != SessionMode.FULL_LOOP) {
            return Optional.empty();
        }

        List<SessionRound> rounds = roundRepo.findBySessionIdOrderByOrdinalAsc(session.getId());
        SessionRound next = rounds.stream()
                .filter(candidate -> candidate.getOrdinal() > completed.getOrdinal())
                .filter(candidate -> candidate.getStatus() == RoundStatus.PENDING)
                .findFirst()
                .orElse(null);
        if (next == null) {
            return Optional.empty();
        }

        next.setCarryOverBrief(handoff(completed, evaluation));
        roundRepo.save(next);

        List<SessionRound> skipped = rounds.stream()
                .filter(candidate -> candidate.getOrdinal() > completed.getOrdinal())
                .filter(candidate -> candidate.getOrdinal() < next.getOrdinal())
                .filter(candidate -> candidate.getStatus() == RoundStatus.SKIPPED)
                .toList();
        log.info("Prepared full-loop round {} after completed round {}", next.getId(), completedRoundId);
        return Optional.of(new RoundAdvance(next, skipped));
    }

    /**
     * Complete a round.
     */
    @Transactional
    public SessionRound completeRound(Long roundId) {
        SessionRound round = roundRepo.findById(roundId)
                .orElseThrow(() -> new NoSuchElementException("Round not found: " + roundId));

        stateMachine.validateRoundTransition(round, RoundStatus.COMPLETED);

        round.setStatus(RoundStatus.COMPLETED);
        round.setPhase(RoundPhase.WRAP);
        round.setEndedAt(Instant.now());

        if (round.getStartedAt() != null) {
            long durationSec = round.getEndedAt().getEpochSecond() - round.getStartedAt().getEpochSecond();
            round.setActualDurationSec((int) durationSec);
        }

        round = roundRepo.save(round);
        log.info("Completed round {} ({})", roundId, round.getModuleType());

        // Check if all rounds are done to auto-complete the session
        checkSessionCompletion(round.getSession().getId());

        return round;
    }

    /**
     * Advance the phase of a round. The LLM requests this via tool calls,
     * but the backend validates and enforces the transition.
     */
    @Transactional
    public SessionRound advancePhase(Long roundId, RoundPhase newPhase) {
        SessionRound round = roundRepo.findById(roundId)
                .orElseThrow(() -> new NoSuchElementException("Round not found: " + roundId));

        stateMachine.validatePhaseTransition(round, newPhase);

        round.setPhase(newPhase);
        round = roundRepo.save(round);
        log.info("Round {} phase: {} → {}", roundId, round.getPhase(), newPhase);
        return round;
    }

    /**
     * Abandon a session.
     */
    @Transactional
    public InterviewSession abandonSession(Long sessionId) {
        InterviewSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));

        session.setStatus(SessionStatus.ABANDONED);
        session.setEndedAt(Instant.now());
        session = sessionRepo.save(session);
        log.info("Abandoned session {}", sessionId);
        return session;
    }

    public InterviewSession getSession(Long sessionId) {
        return sessionRepo.findByIdWithRounds(sessionId)
                .orElseThrow(() -> new NoSuchElementException("Session not found: " + sessionId));
    }

    public List<InterviewSession> listSessions() {
        return sessionRepo.findAllWithRoundsByOrderByStartedAtDesc();
    }

    private String handoff(SessionRound completed, RoundEvaluation evaluation) {
        if (evaluation == null) {
            return "No evaluator handoff was available from the prior round. Assess this round independently.";
        }
        List<String> strengths = readBriefItems(evaluation.getStrengths());
        List<String> gaps = readBriefItems(evaluation.getGaps());
        StringBuilder brief = new StringBuilder("Prior round ")
                .append(completed.getOrdinal()).append(" (").append(completed.getModuleType().getValue())
                .append(") was completed.");
        if (!strengths.isEmpty()) {
            brief.append(" Strengths observed: ").append(String.join("; ", strengths)).append(".");
        }
        if (!gaps.isEmpty()) {
            brief.append(" Areas to probe further: ").append(String.join("; ", gaps)).append(".");
        }
        if (strengths.isEmpty() && gaps.isEmpty()) {
            brief.append(" No specific conclusions were recorded; assess this round independently.");
        }
        return brief.toString();
    }

    private List<String> readBriefItems(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> values = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            List<String> trimmed = new ArrayList<>();
            for (String value : values) {
                if (value != null && !value.isBlank()) {
                    String normalised = value.replaceAll("\\s+", " ").trim();
                    trimmed.add(normalised.substring(0, Math.min(220, normalised.length())));
                }
                if (trimmed.size() == 2) break;
            }
            return trimmed;
        } catch (Exception e) {
            log.warn("Could not read evaluator handoff items: {}", e.getMessage());
            return List.of();
        }
    }

    public record RoundAdvance(SessionRound nextRound, List<SessionRound> skippedRounds) {}

    private void checkSessionCompletion(Long sessionId) {
        InterviewSession session = sessionRepo.findById(sessionId).orElse(null);
        if (session == null) return;

        boolean allDone = session.getRounds().stream()
                .allMatch(r -> r.getStatus() == RoundStatus.COMPLETED || r.getStatus() == RoundStatus.SKIPPED);

        if (allDone) {
            session.setStatus(SessionStatus.COMPLETED);
            session.setEndedAt(Instant.now());
            sessionRepo.save(session);
            log.info("Session {} auto-completed — all rounds done", sessionId);

        }
    }
}
