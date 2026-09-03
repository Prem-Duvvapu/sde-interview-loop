package com.premd.interviewloop.session;

import com.premd.interviewloop.domain.SessionRound;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.domain.enums.RoundPhase;
import com.premd.interviewloop.domain.enums.RoundStatus;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Validates state transitions for rounds and phases.
 * Phase sequences are defined per module type (§1.2).
 *
 * The model requests transitions via tool calls, but THIS class
 * decides whether to honour them — preventing the AI from skipping
 * phases like complexity analysis.
 */
@Component
public class SessionStateMachine {

    /**
     * Phase sequences per module type, per §1.2 of the project plan.
     */
    private static final Map<ModuleType, List<RoundPhase>> PHASE_SEQUENCES;

    static {
        Map<ModuleType, List<RoundPhase>> map = new EnumMap<>(ModuleType.class);

        map.put(ModuleType.DSA, List.of(
                RoundPhase.BRIEFING, RoundPhase.CLARIFYING, RoundPhase.APPROACH,
                RoundPhase.CODING, RoundPhase.COMPLEXITY, RoundPhase.EDGE_CASES,
                RoundPhase.FOLLOW_UP, RoundPhase.WRAP
        ));

        map.put(ModuleType.LLD, List.of(
                RoundPhase.BRIEFING, RoundPhase.REQUIREMENTS, RoundPhase.CLASS_MODEL,
                RoundPhase.DEEP_DIVE, RoundPhase.EXTENSION, RoundPhase.WRAP
        ));

        map.put(ModuleType.HLD, List.of(
                RoundPhase.BRIEFING, RoundPhase.REQUIREMENTS, RoundPhase.ESTIMATION,
                RoundPhase.HIGH_LEVEL, RoundPhase.DEEP_DIVE, RoundPhase.BOTTLENECK,
                RoundPhase.WRAP
        ));

        map.put(ModuleType.CS_FUNDAMENTALS, List.of(
                RoundPhase.BRIEFING, RoundPhase.RAPID_FIRE, RoundPhase.WRAP
        ));

        map.put(ModuleType.JAVA_DEEP_DIVE, List.of(
                RoundPhase.BRIEFING, RoundPhase.SCENARIO, RoundPhase.PROBE,
                RoundPhase.DEPTH_LADDER, RoundPhase.TRADE_OFF, RoundPhase.WRAP
        ));

        map.put(ModuleType.BEHAVIORAL, List.of(
                RoundPhase.BRIEFING, RoundPhase.STORY_SELECTION, RoundPhase.STAR_PROBE,
                RoundPhase.REFLECTION, RoundPhase.WRAP
        ));

        map.put(ModuleType.RESUME, List.of(
                RoundPhase.BRIEFING, RoundPhase.PROJECT_SELECTION, RoundPhase.ROLE_AND_CONTRIBUTION,
                RoundPhase.TECHNICAL_DEEP_DIVE, RoundPhase.IMPACT_AND_METRICS, RoundPhase.WRAP
        ));

        PHASE_SEQUENCES = Collections.unmodifiableMap(map);
    }

    /**
     * Valid round status transitions.
     */
    private static final Map<RoundStatus, Set<RoundStatus>> VALID_ROUND_TRANSITIONS = Map.of(
            RoundStatus.PENDING, Set.of(RoundStatus.IN_PROGRESS, RoundStatus.SKIPPED),
            RoundStatus.IN_PROGRESS, Set.of(RoundStatus.COMPLETED),
            RoundStatus.COMPLETED, Set.of(),
            RoundStatus.SKIPPED, Set.of()
    );

    /**
     * Validate that a round can transition to the target status.
     * @throws IllegalStateException if the transition is not allowed
     */
    public void validateRoundTransition(SessionRound round, RoundStatus target) {
        Set<RoundStatus> allowed = VALID_ROUND_TRANSITIONS.getOrDefault(round.getStatus(), Set.of());
        if (!allowed.contains(target)) {
            throw new IllegalStateException(String.format(
                    "Cannot transition round %d from %s to %s",
                    round.getId(), round.getStatus(), target));
        }
    }

    /**
     * Validate that a phase transition is legal for the round's module type.
     * Phases must advance forward through the defined sequence — no skipping, no going back.
     *
     * @throws IllegalStateException if the transition is not allowed
     */
    public void validatePhaseTransition(SessionRound round, RoundPhase targetPhase) {
        if (round.getStatus() != RoundStatus.IN_PROGRESS) {
            throw new IllegalStateException("Cannot change phase of a round that is not IN_PROGRESS");
        }

        List<RoundPhase> sequence = PHASE_SEQUENCES.get(round.getModuleType());
        if (sequence == null) {
            throw new IllegalStateException("No phase sequence defined for module: " + round.getModuleType());
        }

        int currentIndex = sequence.indexOf(round.getPhase());
        int targetIndex = sequence.indexOf(targetPhase);

        if (targetIndex < 0) {
            throw new IllegalStateException(String.format(
                    "Phase %s is not valid for module %s", targetPhase, round.getModuleType()));
        }

        if (targetIndex <= currentIndex) {
            throw new IllegalStateException(String.format(
                    "Cannot go backwards: %s → %s (module %s)",
                    round.getPhase(), targetPhase, round.getModuleType()));
        }

        // Allow skipping at most one phase forward (flexibility for the AI)
        // but enforce that it cannot skip critical phases
        if (targetIndex > currentIndex + 2) {
            throw new IllegalStateException(String.format(
                    "Cannot skip more than one phase: %s → %s (module %s). " +
                    "Intermediate phases: %s",
                    round.getPhase(), targetPhase, round.getModuleType(),
                    sequence.subList(currentIndex + 1, targetIndex)));
        }
    }

    /**
     * Get the phase sequence for a module type.
     */
    public List<RoundPhase> getPhaseSequence(ModuleType moduleType) {
        return PHASE_SEQUENCES.getOrDefault(moduleType, List.of());
    }

    /**
     * Get the next phase in the sequence, or empty if at the end.
     */
    public Optional<RoundPhase> getNextPhase(ModuleType moduleType, RoundPhase currentPhase) {
        List<RoundPhase> sequence = PHASE_SEQUENCES.get(moduleType);
        if (sequence == null) return Optional.empty();

        int currentIndex = sequence.indexOf(currentPhase);
        if (currentIndex < 0 || currentIndex >= sequence.size() - 1) {
            return Optional.empty();
        }
        return Optional.of(sequence.get(currentIndex + 1));
    }
}
