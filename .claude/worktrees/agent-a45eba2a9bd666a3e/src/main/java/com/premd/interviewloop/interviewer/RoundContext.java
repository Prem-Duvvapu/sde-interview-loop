package com.premd.interviewloop.interviewer;

import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.domain.enums.RoundPhase;

import java.util.List;
import java.util.Map;

/**
 * Everything an {@link InterviewerModule} is allowed to see about the round it is conducting.
 *
 * <p>Built fresh for each turn by the orchestrator. Modules must treat it as read-only —
 * state changes happen through control tool calls, which the backend validates.
 *
 * <p>The split between stable and volatile fields matters: {@code companyProfileId},
 * {@code quirkBehaviors}, {@code emphasis} and {@code question} feed the cached prompt prefix
 * and must not change within a round. {@code phase}, {@code hintLevel} and {@code elapsedSec}
 * are volatile and must only ever reach the model after the last cache breakpoint.
 */
public final class RoundContext {

    private final Long roundId;
    private final Long sessionId;
    private final int roundOrdinal;
    private final ModuleType moduleType;

    // Stable within a round — safe for the cached prefix
    private final String companyProfileId;
    private final String companyDisplayName;
    private final String targetRoleTitle;
    private final String levelCode;
    private final String roundName;
    private final String difficultyTarget;
    private final List<String> quirkBehaviors;
    private final List<String> focusTags;
    private final Map<String, Double> emphasis;
    private final Integer plannedDurationSec;
    private final String questionSlug;
    private final String questionContentHash;

    // Volatile — must stay out of the cached prefix
    private final RoundPhase phase;
    private final int hintLevel;
    private final int elapsedSec;

    private RoundContext(Builder b) {
        this.roundId = b.roundId;
        this.sessionId = b.sessionId;
        this.roundOrdinal = b.roundOrdinal;
        this.moduleType = b.moduleType;
        this.companyProfileId = b.companyProfileId;
        this.companyDisplayName = b.companyDisplayName;
        this.targetRoleTitle = b.targetRoleTitle;
        this.levelCode = b.levelCode;
        this.roundName = b.roundName;
        this.difficultyTarget = b.difficultyTarget;
        this.quirkBehaviors = b.quirkBehaviors == null ? List.of() : List.copyOf(b.quirkBehaviors);
        this.focusTags = b.focusTags == null ? List.of() : List.copyOf(b.focusTags);
        this.emphasis = b.emphasis == null ? Map.of() : Map.copyOf(b.emphasis);
        this.plannedDurationSec = b.plannedDurationSec;
        this.questionSlug = b.questionSlug;
        this.questionContentHash = b.questionContentHash;
        this.phase = b.phase;
        this.hintLevel = b.hintLevel;
        this.elapsedSec = b.elapsedSec;
    }

    public Long roundId() { return roundId; }
    public Long sessionId() { return sessionId; }
    public int roundOrdinal() { return roundOrdinal; }
    public ModuleType moduleType() { return moduleType; }
    public String companyProfileId() { return companyProfileId; }
    public String companyDisplayName() { return companyDisplayName; }
    public String targetRoleTitle() { return targetRoleTitle; }
    public String levelCode() { return levelCode; }
    public String roundName() { return roundName; }
    public String difficultyTarget() { return difficultyTarget; }

    /**
     * Verbatim {@code quirks[].interviewer_behavior} fragments for this round ordinal.
     * These are prompt code, not prose — inject them unchanged.
     */
    public List<String> quirkBehaviors() { return quirkBehaviors; }

    public List<String> focusTags() { return focusTags; }
    public Map<String, Double> emphasis() { return emphasis; }
    public Integer plannedDurationSec() { return plannedDurationSec; }

    /**
     * Slug of the question chosen for this round, or null before selection has happened.
     * A module resolves the full statement from its own bank — the statement text is never
     * copied into the context, so the bank stays the single source of truth.
     */
    public String questionSlug() { return questionSlug; }

    /** Hash of the statement as it was when selected, for interpreting past rounds after edits. */
    public String questionContentHash() { return questionContentHash; }

    public RoundPhase phase() { return phase; }

    /** 0 = no help given. Rises only when the backend honours a set_hint_level call. */
    public int hintLevel() { return hintLevel; }

    public int elapsedSec() { return elapsedSec; }

    /** Seconds left against the planned duration, or null if the round is untimed. */
    public Integer remainingSec() {
        return plannedDurationSec == null ? null : Math.max(0, plannedDurationSec - elapsedSec);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private Long roundId;
        private Long sessionId;
        private int roundOrdinal;
        private ModuleType moduleType;
        private String companyProfileId;
        private String companyDisplayName;
        private String targetRoleTitle;
        private String levelCode;
        private String roundName;
        private String difficultyTarget;
        private List<String> quirkBehaviors;
        private List<String> focusTags;
        private Map<String, Double> emphasis;
        private Integer plannedDurationSec;
        private String questionSlug;
        private String questionContentHash;
        private RoundPhase phase = RoundPhase.BRIEFING;
        private int hintLevel;
        private int elapsedSec;

        public Builder roundId(Long v) { this.roundId = v; return this; }
        public Builder sessionId(Long v) { this.sessionId = v; return this; }
        public Builder roundOrdinal(int v) { this.roundOrdinal = v; return this; }
        public Builder moduleType(ModuleType v) { this.moduleType = v; return this; }
        public Builder companyProfileId(String v) { this.companyProfileId = v; return this; }
        public Builder companyDisplayName(String v) { this.companyDisplayName = v; return this; }
        public Builder targetRoleTitle(String v) { this.targetRoleTitle = v; return this; }
        public Builder levelCode(String v) { this.levelCode = v; return this; }
        public Builder roundName(String v) { this.roundName = v; return this; }
        public Builder difficultyTarget(String v) { this.difficultyTarget = v; return this; }
        public Builder quirkBehaviors(List<String> v) { this.quirkBehaviors = v; return this; }
        public Builder focusTags(List<String> v) { this.focusTags = v; return this; }
        public Builder emphasis(Map<String, Double> v) { this.emphasis = v; return this; }
        public Builder plannedDurationSec(Integer v) { this.plannedDurationSec = v; return this; }
        public Builder questionSlug(String v) { this.questionSlug = v; return this; }
        public Builder questionContentHash(String v) { this.questionContentHash = v; return this; }
        public Builder phase(RoundPhase v) { this.phase = v; return this; }
        public Builder hintLevel(int v) { this.hintLevel = v; return this; }
        public Builder elapsedSec(int v) { this.elapsedSec = v; return this; }

        public RoundContext build() {
            if (moduleType == null) throw new IllegalStateException("moduleType is required");
            return new RoundContext(this);
        }
    }
}
