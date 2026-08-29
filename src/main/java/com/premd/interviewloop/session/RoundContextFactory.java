package com.premd.interviewloop.session;

import com.premd.interviewloop.domain.SessionRound;
import com.premd.interviewloop.domain.repository.SessionRoundRepository;
import com.premd.interviewloop.interviewer.RoundContext;
import com.premd.interviewloop.profile.CompanyProfile;
import com.premd.interviewloop.profile.ProfileLoader;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Builds the detached {@link RoundContext} an interviewer module sees.
 *
 * <p>Separate from the orchestrator on purpose. The round's session association is lazy, and the
 * company profile has to be walked to collect quirks, so the context has to be assembled inside a
 * transaction — while the streaming turn itself must run outside one, so a slow provider does not
 * hold a database connection open for the length of a model response.
 */
@Component
public class RoundContextFactory {

    private final SessionRoundRepository roundRepo;
    private final ProfileLoader profileLoader;

    public RoundContextFactory(SessionRoundRepository roundRepo, ProfileLoader profileLoader) {
        this.roundRepo = roundRepo;
        this.profileLoader = profileLoader;
    }

    @Transactional(readOnly = true)
    public RoundContext build(Long roundId, int hintLevel) {
        SessionRound round = roundRepo.findById(roundId)
                .orElseThrow(() -> new NoSuchElementException("Round not found: " + roundId));

        String profileId = round.getSession().getCompanyProfileId();
        CompanyProfile profile = profileId != null && profileLoader.hasProfile(profileId)
                ? profileLoader.getProfile(profileId)
                : null;

        RoundContext.Builder builder = RoundContext.builder()
                .roundId(round.getId())
                .sessionId(round.getSession().getId())
                .roundOrdinal(round.getOrdinal())
                .moduleType(round.getModuleType())
                .companyProfileId(profileId)
                .difficultyTarget(round.getDifficultyTarget())
                .plannedDurationSec(round.getPlannedDurationSec())
                .carryOverBrief(round.getCarryOverBrief())
                .phase(round.getPhase())
                .hintLevel(hintLevel)
                .elapsedSec(elapsedSec(round));

        // The statement lives in the module's bank; the context carries only its identity, so the
        // bank stays the single source of truth and the same question re-renders on every turn.
        builder.questionSlug(round.getQuestionSlug())
                .questionContentHash(round.getQuestionContentHash());

        if (profile != null) {
            builder.companyDisplayName(profile.getDisplayName())
                    .emphasis(profile.getEmphasis())
                    .quirkBehaviors(quirkBehaviorsFor(profile, round.getOrdinal()));

            if (profile.getTargetRole() != null) {
                builder.targetRoleTitle(profile.getTargetRole().getTitle())
                        .levelCode(profile.getTargetRole().getLevelCode());
            }

            profileRound(profile, round.getOrdinal()).ifPresent(pr ->
                    builder.roundName(pr.getName()).focusTags(pr.getFocusTags()));
        }

        return builder.build();
    }

    /**
     * The verbatim {@code interviewer_behavior} fragments that apply to this round ordinal.
     * These are injected into the prompt unchanged — they are prompt code, not prose.
     */
    private List<String> quirkBehaviorsFor(CompanyProfile profile, int roundOrdinal) {
        List<String> behaviors = new ArrayList<>();
        if (profile.getQuirks() == null) {
            return behaviors;
        }
        for (CompanyProfile.Quirk quirk : profile.getQuirks()) {
            if (quirk.appliesTo(roundOrdinal)
                    && quirk.getInterviewerBehavior() != null
                    && !quirk.getInterviewerBehavior().isBlank()) {
                behaviors.add(quirk.getInterviewerBehavior());
            }
        }
        return behaviors;
    }

    private java.util.Optional<CompanyProfile.Round> profileRound(CompanyProfile profile, int ordinal) {
        if (profile.getLoop() == null || profile.getLoop().getRounds() == null) {
            return java.util.Optional.empty();
        }
        return profile.getLoop().getRounds().stream()
                .filter(r -> r.getOrdinal() == ordinal)
                .findFirst();
    }

    private int elapsedSec(SessionRound round) {
        if (round.getStartedAt() == null) {
            return 0;
        }
        Instant end = round.getEndedAt() != null ? round.getEndedAt() : Instant.now();
        return (int) Math.max(0, end.getEpochSecond() - round.getStartedAt().getEpochSecond());
    }
}
