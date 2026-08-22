package com.premd.interviewloop.interviewer;

import com.premd.interviewloop.domain.enums.ArtifactKind;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.llm.LlmRequest;

import java.util.List;

/**
 * SPI for a round-conducting interviewer module (DSA, LLD, HLD, CS fundamentals, Java deep-dive).
 *
 * <p><b>Frozen at the end of Phase 1.</b> Phases 2–5 implement this interface in parallel;
 * changing it after those phases start means renegotiating an interface across four
 * in-flight branches. Add capabilities with default methods rather than new abstract ones.
 *
 * <h2>Why the methods are split this way</h2>
 *
 * The division is not cosmetic — it mirrors the cache-stable prompt layout (PROJECT_PLAN.md §1.4).
 * Everything from {@link #tools()} through {@link #problemBlock} is <b>stable for the whole
 * round</b> and forms the cached prefix. {@link #phaseDirective} is <b>volatile</b> and is placed
 * after the last cache breakpoint, alongside the candidate's latest artifact.
 *
 * <p>An implementation that folds phase or timing information into {@link #persona} or
 * {@link #problemBlock} will silently destroy prompt caching on every turn — the request will
 * still work, it will just cost several times more and stream slower. That failure is invisible
 * without checking {@code cache_read_tokens} in the cost ledger.
 *
 * <h2>What the module does not control</h2>
 *
 * A module <i>proposes</i>; the backend <i>disposes</i>. Phase advancement, hint escalation and
 * round termination are requested by the model through the control tools and validated by
 * {@code SessionStateMachine}. A module cannot move its own round forward.
 */
public interface InterviewerModule {

    /** The module type this implementation conducts. One implementation per type. */
    ModuleType moduleType();

    /**
     * Choose a question for the round. Called once, when the round starts.
     * Implementations should honour {@code ctx.difficultyTarget()} and avoid repeating
     * questions the candidate has recently seen.
     */
    QuestionSelection selectQuestion(RoundContext ctx);

    /**
     * The module's scoring rubric, rendered as prompt text.
     *
     * <p>Must be identical for every round of this module at a given {@link #rubricVersion()} —
     * it is the first and most stable block in the cached prefix. No interpolation of
     * candidate, company or timing data.
     */
    String rubric();

    /** Rubric version, recorded on evaluations so scores stay interpretable across rubric edits. */
    String rubricVersion();

    /**
     * Interviewer persona for this round, including the company's verbatim
     * {@code quirks[].interviewer_behavior} fragments from {@code ctx.quirkBehaviors()}.
     *
     * <p>Stable for the whole round. Calibrate to SDE-2: the candidate has roughly two years
     * of experience, and a round pitched at senior level produces a useless signal.
     */
    String persona(RoundContext ctx);

    /**
     * The problem statement block, rendered from {@code ctx.question()}.
     * Stable for the whole round; this is the last block before the cache breakpoint.
     */
    String problemBlock(RoundContext ctx);

    /**
     * What the interviewer should be doing <i>right now</i>, given the current phase,
     * hint level and remaining time.
     *
     * <p>This is the volatile half of the prompt and the reason the interviewer behaves like a
     * state machine rather than a chatbot. It is injected after the cache breakpoint, so it may
     * freely reference {@code ctx.phase()}, {@code ctx.hintLevel()} and {@code ctx.remainingSec()}.
     */
    String phaseDirective(RoundContext ctx);

    /**
     * The opening message the interviewer sends before the candidate has said anything.
     * Persisted as the first transcript turn of the round.
     */
    String openingBrief(RoundContext ctx);

    /** Control tools exposed to the model. Defaults to the standard four. */
    default List<LlmRequest.Tool> tools() {
        return InterviewerTools.standard();
    }

    /** The kind of artifact this module's work surface produces. */
    default ArtifactKind artifactKind() {
        return ArtifactKind.SCRATCH;
    }

    /** Editor language hint for the artifact surface, or null when not code. */
    default String artifactLanguage() {
        return null;
    }

    /** Cap on interviewer response length. Interview turns should be short; long ones flatter. */
    default int maxResponseTokens() {
        return 1536;
    }
}
