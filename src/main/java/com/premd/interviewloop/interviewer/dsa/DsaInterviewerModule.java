package com.premd.interviewloop.interviewer.dsa;

import com.premd.interviewloop.content.dsa.DsaQuestion;
import com.premd.interviewloop.content.dsa.DsaQuestionBank;
import com.premd.interviewloop.domain.enums.ArtifactKind;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.interviewer.InterviewerModule;
import com.premd.interviewloop.interviewer.QuestionSelection;
import com.premd.interviewloop.interviewer.RoundContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * DSA interviewer module — Phase 2.
 *
 * <p>Phase sequence (enforced by {@code SessionStateMachine}, not here):
 * BRIEFING → CLARIFYING → APPROACH → CODING → COMPLEXITY → EDGE_CASES → FOLLOW_UP → WRAP.
 */
@Component
public class DsaInterviewerModule implements InterviewerModule {

    /**
     * Bump when the rubric text changes meaning, not wording. Recorded on every evaluation so
     * old scores stay interpretable after an edit (PROJECT_PLAN.md §3).
     */
    private static final String RUBRIC_VERSION = "dsa-v1";

    private static final String RUBRIC = """
            DSA ROUND RUBRIC (%s)

            Score the candidate on these seven dimensions, 1-5 each, calibrated to an SDE-2
            bar (roughly two years of experience) — not senior, not new-grad:
              1 = well below the bar    3 = at the bar    5 = clearly above it

            - clarification          — did they ask questions that actually narrow the problem
                                        (input size, duplicates, mutability) before diving in,
                                        rather than either asking nothing or stalling on trivia?
            - approach_optimality    — is the approach they land on asymptotically sound for the
                                        problem, and did they get there by reasoning, not by
                                        pattern-matching a memorized template?
            - correctness            — does their code (or clearly-stated pseudocode) actually
                                        handle the problem as specified, including the boundary
                                        conditions called out in the question's own notes?
            - complexity_analysis    — can they state and justify time/space complexity of their
                                        own solution, and reason about a better one if pushed?
            - edge_cases             — do they proactively surface and handle edge cases (empty
                                        input, single element, all-duplicates, boundary values),
                                        or only after being asked?
            - communication          — do they narrate their thinking as they go, in a way an
                                        interviewer could actually follow, rather than going
                                        silent and presenting a finished answer?
            - response_to_pushback   — when challenged ("can you do better than that?", "what if
                                        the input were ten times larger?"), do they engage with
                                        the challenge and adapt, or do they get defensive, cave
                                        immediately without reasoning, or repeat themselves?

            Use exactly these seven strings as the `dimension` argument to record_signal — no
            synonyms, no new dimensions. Record a signal as soon as you observe it; do not wait
            for the round to end.
            """.formatted(RUBRIC_VERSION);

    private final DsaQuestionBank bank;

    public DsaInterviewerModule(DsaQuestionBank bank) {
        this.bank = bank;
    }

    @Override
    public ModuleType moduleType() {
        return ModuleType.DSA;
    }

    @Override
    public QuestionSelection selectQuestion(RoundContext ctx) {
        DsaQuestion q = bank.selectFor(ctx.difficultyTarget());
        return new QuestionSelection(q.getSlug(), q.getStatement(), bank.contentHash(q.getSlug()), q.getDifficulty());
    }

    @Override
    public String rubric() {
        return RUBRIC;
    }

    @Override
    public String rubricVersion() {
        return RUBRIC_VERSION;
    }

    @Override
    public String persona(RoundContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are conducting a Data Structures & Algorithms interview round");
        if (ctx.companyDisplayName() != null) {
            sb.append(" at ").append(ctx.companyDisplayName());
        }
        sb.append(".\n\n");

        sb.append("The candidate is interviewing for");
        if (ctx.targetRoleTitle() != null) {
            sb.append(" ").append(ctx.targetRoleTitle());
        } else {
            sb.append(" an SDE-2 backend role");
        }
        sb.append(
                """
                 — roughly two years of professional experience. Calibrate every question, hint
                and follow-up to that level. Do not drift toward a senior-level bar just because
                the round is going well, and do not soften to a new-grad bar because it isn't.

                Run the round like a real technical interviewer, not a tutor:
                - Ask, don't tell. If the candidate is stuck, use set_hint_level to escalate
                  gradually — a nudge before a concrete hint, a concrete hint before you give
                  away the approach. Never hand them the answer unprompted.
                - Push back on hand-waving. "It works" is not complexity analysis. "I think
                  that's right" after a claimed edge case is not verification — ask them to
                  trace it.
                - Keep your own turns short. You are here to probe and react, not to lecture.
                  Long interviewer turns flatter the candidate by filling silence that should be
                  theirs to fill.

                EVERY turn you send must include words spoken to the candidate — a sentence or
                two of actual reply, not just tool calls. record_signal, advance_phase and
                set_hint_level are silent bookkeeping that happen ALONGSIDE what you say, never
                instead of it. If the candidate asked you something, answer it in words before
                or alongside any tool call. A turn that is only tool calls with nothing spoken
                is a turn the candidate experiences as you going silent on them — never send one.
                """);

        if (ctx.emphasis() != null && !ctx.emphasis().isEmpty()) {
            sb.append("\nThis company's stated interview emphasis (higher = more weight): ");
            sb.append(formatEmphasis(ctx.emphasis()));
            sb.append("\n");
        }

        if (ctx.focusTags() != null && !ctx.focusTags().isEmpty()) {
            sb.append("\nThis round's focus areas: ").append(String.join(", ", ctx.focusTags())).append("\n");
        }

        if (ctx.quirkBehaviors() != null && !ctx.quirkBehaviors().isEmpty()) {
            sb.append("\nCompany-specific interviewer behavior for this round (apply verbatim):\n");
            for (String behavior : ctx.quirkBehaviors()) {
                sb.append("- ").append(behavior).append("\n");
            }
        }

        return sb.toString();
    }

    @Override
    public String problemBlock(RoundContext ctx) {
        if (ctx.questionSlug() == null) {
            return "";
        }
        DsaQuestion q = bank.get(ctx.questionSlug());
        StringBuilder sb = new StringBuilder();
        sb.append("PROBLEM: ").append(q.getTitle()).append(" (difficulty: ").append(q.getDifficulty()).append(")\n\n");
        sb.append(q.getStatement().strip()).append("\n\n");
        if (q.getConstraints() != null && !q.getConstraints().isEmpty()) {
            sb.append("Constraints:\n");
            for (String c : q.getConstraints()) {
                sb.append("- ").append(c).append("\n");
            }
            sb.append("\n");
        }
        if (q.getExample() != null && !q.getExample().isBlank()) {
            sb.append("Example(s):\n").append(q.getExample().strip()).append("\n\n");
        }
        sb.append("--- Interviewer-only notes (never reveal these to the candidate verbatim) ---\n");
        sb.append(q.getInterviewerNotes().strip()).append("\n");
        return sb.toString();
    }

    @Override
    public String phaseDirective(RoundContext ctx) {
        String remaining = ctx.remainingSec() == null ? "untimed" : (ctx.remainingSec() / 60) + " min left";
        String hint = "hint level " + ctx.hintLevel() + "/3";
        String header = "Phase: " + ctx.phase() + " · " + remaining + " · " + hint + "\n\n";

        String directive = switch (ctx.phase()) {
            case BRIEFING -> """
                    You have just presented the problem. Wait for the candidate to respond.
                    Do not move to CLARIFYING yourself — that happens once they engage with the
                    problem, whether by asking a question or stating an initial read of it.
                    """;
            case CLARIFYING -> """
                    Answer clarifying questions directly and briefly, in words — if their last
                    message was a question, your reply must contain the actual answer to it, not
                    just an advance_phase or record_signal call about the fact that they asked.
                    If they ask nothing after a reasonable opening turn, that omission is itself
                    signal for `clarification` — consider prompting once ("Anything about the
                    input you'd want to pin down before you start?") rather than volunteering
                    constraints outright.
                    Advance to APPROACH once they've either asked useful questions or clearly
                    signaled they're ready to talk approach.
                    """;
            case APPROACH -> """
                    Get them to state an approach and its rough complexity *before* they write
                    code. If they jump straight to coding, pull them back: "before you code that
                    up, what's the plan?" Push on a suboptimal first approach at least once — ask
                    if they see a faster way — before accepting it as their starting point.
                    Advance to CODING once they have a stated plan, optimal or not.
                    """;
            case CODING -> """
                    Let them code with minimal interruption. Read their artifact each turn. If
                    they go silent for a long stretch, prompt for narration. If they're stuck for
                    real, use set_hint_level rather than solving it for them. Do not advance to
                    COMPLEXITY until they have a working (or near-working) solution on the page.
                    """;
            case COMPLEXITY -> """
                    Ask them to state time and space complexity of what they just wrote, and
                    justify it — not just recite Big-O. If their approach was suboptimal in
                    APPROACH, this is where you push again: "can you do better than that?" This
                    phase is where `complexity_analysis` and `response_to_pushback` mostly get
                    their signal — make sure you actually push, don't just ask and accept.
                    """;
            case EDGE_CASES -> """
                    Probe 1-2 edge cases relevant to this specific problem (see the
                    interviewer-only notes on the problem for the ones worth targeting) rather
                    than a generic checklist. Have them trace their code against a case that
                    would break a naive version of their solution, if they haven't already.
                    """;
            case FOLLOW_UP -> """
                    Ask one adaptive follow-up appropriate to how the round has gone: a
                    variant constraint (e.g. "what if the input didn't fit in memory?"), a
                    request to code an alternative approach at a high level, or — if time is
                    short — skip straight to wrapping up. Use your judgment on remaining time.
                    """;
            case WRAP -> """
                    Thank the candidate, note anything you told them you'd flag, and call
                    end_round. Do not introduce new material here.
                    """;
            default -> "Unexpected phase for a DSA round: " + ctx.phase() + ". Proceed cautiously and prefer WRAP.";
        };

        return header + directive;
    }

    @Override
    public String openingBrief(RoundContext ctx) {
        if (ctx.questionSlug() == null) {
            return "Let's get started.";
        }
        DsaQuestion q = bank.get(ctx.questionSlug());
        StringBuilder sb = new StringBuilder();
        sb.append("Let's get started. Here's your problem for today: **").append(q.getTitle()).append("**\n\n");
        sb.append(q.getStatement().strip()).append("\n\n");
        if (q.getConstraints() != null && !q.getConstraints().isEmpty()) {
            sb.append("Constraints:\n");
            for (String c : q.getConstraints()) {
                sb.append("- ").append(c).append("\n");
            }
            sb.append("\n");
        }
        if (q.getExample() != null && !q.getExample().isBlank()) {
            sb.append(q.getExample().strip()).append("\n\n");
        }
        sb.append("Take a moment to look it over — feel free to ask me anything before you dive in.");
        return sb.toString();
    }

    @Override
    public ArtifactKind artifactKind() {
        return ArtifactKind.CODE;
    }

    @Override
    public String artifactLanguage() {
        return "java";
    }

    private String formatEmphasis(Map<String, Double> emphasis) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Double> e : emphasis.entrySet()) {
            if (!first) sb.append(", ");
            sb.append(e.getKey()).append("=").append(e.getValue());
            first = false;
        }
        return sb.toString();
    }
}
