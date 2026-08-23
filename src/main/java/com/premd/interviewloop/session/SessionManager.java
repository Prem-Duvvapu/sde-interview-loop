package com.premd.interviewloop.session;

import com.premd.interviewloop.domain.InterviewSession;
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
import java.util.List;
import java.util.NoSuchElementException;

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

    public SessionManager(InterviewSessionRepository sessionRepo,
                          SessionRoundRepository roundRepo,
                          ProfileLoader profileLoader,
                          SessionStateMachine stateMachine) {
        this.sessionRepo = sessionRepo;
        this.roundRepo = roundRepo;
        this.profileLoader = profileLoader;
        this.stateMachine = stateMachine;
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

        stateMachine.validateRoundTransition(round, RoundStatus.IN_PROGRESS);

        round.setStatus(RoundStatus.IN_PROGRESS);
        round.setPhase(RoundPhase.BRIEFING);
        round.setStartedAt(Instant.now());

        round = roundRepo.save(round);
        log.info("Started round {} ({})", roundId, round.getModuleType());
        return round;
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
