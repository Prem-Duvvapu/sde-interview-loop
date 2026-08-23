package com.premd.interviewloop.interviewer.lld;

import com.premd.interviewloop.content.lld.LldQuestion;
import com.premd.interviewloop.content.lld.LldQuestionBank;
import com.premd.interviewloop.domain.enums.ArtifactKind;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.interviewer.InterviewerModule;
import com.premd.interviewloop.interviewer.QuestionSelection;
import com.premd.interviewloop.interviewer.RoundContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * LLD interviewer module — Phase 3.
 *
 * <p>Phase sequence (enforced by {@code SessionStateMachine}, not here):
 * BRIEFING → REQUIREMENTS → CLASS_MODEL → DEEP_DIVE → EXTENSION → WRAP.
 *
 * <p>The class model surface reuses the same Monaco/code artifact the DSA module uses
 * (ArtifactKind.CODE) rather than a dedicated diagramming UI — a class model is naturally
 * expressed as Java interfaces and class skeletons, unlike HLD's node/edge component graph
 * (DM-2), so no new frontend surface is needed for this module.
 */
@Component
public class LldInterviewerModule implements InterviewerModule {

    private static final String RUBRIC_VERSION = "lld-v1";

    private static final String RUBRIC = """
            LLD ROUND RUBRIC (%s)

            Score the candidate on these six dimensions, 1-5 each, calibrated to an SDE-2 bar
            (roughly two years of experience) — not senior, not new-grad. The bar for LLD in
            particular should be set high, not soft: a candidate with real production
            experience should be pushed on the same things a real bar-raiser would push on.
              1 = well below the bar    3 = at the bar    5 = clearly above it

            - requirement_extraction — did they ask questions that actually narrow scope and
                                        surface non-obvious requirements (scale, concurrency,
                                        what's explicitly out of scope), rather than diving
                                        straight into classes on a guessed-at spec?
            - class_model             — do the classes/interfaces they produce actually model
                                        the problem's real entities and relationships, with
                                        sensible responsibilities per class, not a pile of
                                        static methods or one god-class doing everything?
            - solid_adherence         — does the design hold up against SOLID, in particular
                                        open/closed — can a plausible near-future requirement
                                        change be accommodated without rewriting the core?
            - extensibility           — when pushed with a concrete "what changes if X is
                                        added later" question, can they name the actual seam,
                                        or do they hand-wave / need the answer suggested to them?
            - concurrency_handling    — for designs with concurrent access (most of them),
                                        do they identify the actual race conditions and reach
                                        for a real fix, not just say "add synchronized" without
                                        knowing what it protects?
            - code_quality            — are the class/method signatures they actually wrote
                                        (not just described verbally) coherent Java, with
                                        sensible types and names, not just talk with no code?

            Use exactly these six strings as the `dimension` argument to record_signal — no
            synonyms, no new dimensions. Record a signal as soon as you observe it; do not wait
            for the round to end.
            """.formatted(RUBRIC_VERSION);

    private final LldQuestionBank bank;

    public LldInterviewerModule(LldQuestionBank bank) {
        this.bank = bank;
    }

    @Override
    public ModuleType moduleType() {
        return ModuleType.LLD;
    }

    @Override
    public QuestionSelection selectQuestion(RoundContext ctx) {
        LldQuestion q = bank.selectFor(ctx.difficultyTarget());
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
        sb.append("You are conducting a Low-Level Design (LLD) interview round");
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
                 — roughly two years of professional experience, typically with a real
                production portfolio behind them. Calibrate to that level, but do not go soft:
                a candidate with real project experience gets no value from an LLD round that
                doesn't push back the way a real bar-raiser would. Do not drift toward
                staff/principal-level scope (no multi-service architecture, no distributed
                systems trade-offs — that's HLD territory, not this round), but within SDE-2
                LLD scope, push hard.

                Run the round like a real technical interviewer, not a tutor:
                - Ask, don't tell. If the candidate is stuck, use set_hint_level to escalate
                  gradually. Never hand them the class model unprompted.
                - Push on hand-waving specifically. "I'd use a strategy pattern there" without
                  actually naming the interface and its method signature is not a design — ask
                  them to write it. Verbal-only answers with no code in the editor are a real
                  gap for code_quality, not just a style preference.
                - Every "what if X changes later" question should get a concrete answer about
                  which class or interface absorbs the change — "I'd refactor it" without saying
                  what refactor is not extensibility, it's a dodge.
                - Keep your own turns short. You are here to probe and react, not to lecture.

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
        LldQuestion q = bank.get(ctx.questionSlug());
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
            sb.append("Example:\n").append(q.getExample().strip()).append("\n\n");
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
                    Do not move to REQUIREMENTS yourself — that happens once they engage with
                    the problem.
                    """;
            case REQUIREMENTS -> """
                    Push for real requirement extraction, in words — if their last message was
                    a question, answer it directly. Do not let them skip straight to classes
                    without at least naming functional scope, expected scale/concurrency, and
                    what's explicitly out of scope. If they ask nothing, prompt once ("Anything
                    about scale or concurrency you'd want to pin down first?") rather than
                    volunteering the constraints outright.
                    Advance to CLASS_MODEL once requirements are reasonably pinned down.
                    """;
            case CLASS_MODEL -> """
                    Have them write actual classes/interfaces in the editor, not just describe
                    them verbally — a verbal-only design is a code_quality gap worth noting.
                    Ask about responsibilities as they go: "what does this class own, and what
                    does it deliberately not own?" Advance to DEEP_DIVE once a reasonable class
                    model exists on the page, even if incomplete.
                    """;
            case DEEP_DIVE -> """
                    Pick ONE specific mechanism from the interviewer-only notes above (usually
                    the concurrency correctness point) and push on it directly — ask them to
                    walk through what happens under concurrent access to their design
                    specifically, not in the abstract. This phase is where
                    concurrency_handling and class_model mostly get their signal — make sure
                    you actually push, don't just ask once and move on.
                    """;
            case EXTENSION -> """
                    Ask a concrete "what changes if X is added later" question drawn from the
                    interviewer-only notes' SOLID/extensibility probe. Require a specific answer
                    naming the actual class or interface that absorbs the change — "I'd
                    refactor" with no specifics is not a pass here, push back on it once before
                    accepting an answer that vague.
                    """;
            case WRAP -> """
                    Thank the candidate, note anything you told them you'd flag, and call
                    end_round. Do not introduce new material here.
                    """;
            default -> "Unexpected phase for an LLD round: " + ctx.phase() + ". Proceed cautiously and prefer WRAP.";
        };

        return header + directive;
    }

    @Override
    public String openingBrief(RoundContext ctx) {
        if (ctx.questionSlug() == null) {
            return "Let's get started.";
        }
        LldQuestion q = bank.get(ctx.questionSlug());
        StringBuilder sb = new StringBuilder();
        sb.append("Let's get started. Here's your design problem for today: **").append(q.getTitle()).append("**\n\n");
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
        sb.append("Take a moment to think about requirements before jumping into classes — "
                + "feel free to ask me anything.");
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
