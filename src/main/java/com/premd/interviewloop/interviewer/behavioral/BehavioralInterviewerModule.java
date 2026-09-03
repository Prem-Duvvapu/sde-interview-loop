package com.premd.interviewloop.interviewer.behavioral;

import com.premd.interviewloop.content.behavioral.BehavioralQuestion;
import com.premd.interviewloop.content.behavioral.BehavioralQuestionBank;
import com.premd.interviewloop.domain.enums.ArtifactKind;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.interviewer.InterviewerModule;
import com.premd.interviewloop.interviewer.QuestionSelection;
import com.premd.interviewloop.interviewer.RoundContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Behavioral interviewer module.
 *
 * <p>Phase sequence: BRIEFING → STORY_SELECTION → STAR_PROBE → REFLECTION → WRAP.
 * Resolves D-6 in PROJECT_PLAN.md — a thin behavioral module, per the option that
 * document names. One question per round, from a bank of common (not proprietary)
 * behavioral prompts. If a resume is on file, the persona invites the candidate to
 * ground their story in a real project from it, but the round works fine without one —
 * this module does not require {@code interviewer.resume.ResumeInterviewerModule}.
 */
@Component
public class BehavioralInterviewerModule implements InterviewerModule {

    private static final String RUBRIC_VERSION = "behavioral-v1";

    private static final String RUBRIC = """
            BEHAVIORAL ROUND RUBRIC (%s)

            Score the candidate on these five dimensions, 1-5 each, calibrated to an SDE-2 bar
            (roughly two years of experience) — not senior, not new-grad.
              1 = well below the bar    3 = at the bar    5 = clearly above it

            - specificity             — concrete situation, concrete actions, concrete numbers
                                        where relevant — or generic, could-be-anyone platitudes?
                                        "We worked hard and fixed it" scores low regardless of how
                                        confidently it's delivered.
            - ownership               — do they own their own role and their own mistakes, using
                                        "I" at the moments that were actually theirs — or do they
                                        externalise blame and take credit only for successes?
            - self_awareness          — genuine reflection on what they'd do differently, or a
                                        rehearsed "we won" narrative with no acknowledged rough
                                        edge? A credible flaw beats a suspiciously perfect story.
            - impact_and_result       — is there a clear, plausible outcome, ideally with some
                                        measure of it — or does the story just stop after the
                                        action with no stated result?
            - communication_structure — is the story structured and easy to follow (roughly
                                        situation → action → result), or rambling and hard to
                                        extract the actual sequence of events from?

            Use exactly these five strings as the `dimension` argument to record_signal — no
            synonyms, no new dimensions. Record a signal as soon as you observe it; do not wait
            for the round to end.
            """.formatted(RUBRIC_VERSION);

    private final BehavioralQuestionBank bank;

    public BehavioralInterviewerModule(BehavioralQuestionBank bank) {
        this.bank = bank;
    }

    @Override
    public ModuleType moduleType() {
        return ModuleType.BEHAVIORAL;
    }

    @Override
    public QuestionSelection selectQuestion(RoundContext ctx) {
        BehavioralQuestion q = bank.selectFor(ctx.difficultyTarget());
        return new QuestionSelection(q.getSlug(), q.getPrompt(), bank.contentHash(q.getSlug()), q.getDifficulty());
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
        sb.append("You are conducting a behavioral interview round");
        if (ctx.companyDisplayName() != null) {
            sb.append(" at ").append(ctx.companyDisplayName());
        }
        sb.append(".\n\n");

        sb.append(
                """
                The candidate is interviewing for an SDE-2 backend role — roughly two years of
                professional experience. This round is one question, explored deeply, not a
                checklist of many. Real behavioral rounds are conversational, not interrogations —
                be warm, but do not let vagueness slide.

                Run it like a real interviewer who has heard a thousand rehearsed answers:
                - Let them tell the story mostly uninterrupted first. Then probe the specific gaps
                  — usually: what exactly did YOU do (not the team), what was the actual result,
                  and what would they do differently now.
                - "We" is not "I". If they describe a team effort, ask directly what their own
                  individual contribution was.
                - Push once, gently, on any answer that ends without a stated result — "how did
                  that turn out?" A story that just stops after the action is incomplete.
                - Do not accept a suspiciously flawless narrative without one gentle probe for
                  self-awareness — "looking back, is there anything you'd do differently?"
                - Do NOT announce which rubric dimension you're probing. Conduct it naturally, the
                  way a real interviewer would.
                - Keep your own turns short — this round belongs to the candidate's story, not
                  your commentary on it.

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

        if (ctx.quirkBehaviors() != null && !ctx.quirkBehaviors().isEmpty()) {
            sb.append("\nCompany-specific interviewer behavior for this round (apply verbatim):\n");
            for (String behavior : ctx.quirkBehaviors()) {
                sb.append("- ").append(behavior).append("\n");
            }
        }

        appendPanelHandoff(sb, ctx);
        return sb.toString();
    }

    private void appendPanelHandoff(StringBuilder sb, RoundContext ctx) {
        if (ctx.carryOverBrief() != null && !ctx.carryOverBrief().isBlank()) {
            sb.append("\nPrivate panel handoff — do not mention this note to the candidate: ")
                    .append(ctx.carryOverBrief())
                    .append(" Assess this round on its own evidence; use the handoff only to choose useful probes.\n");
        }
    }

    @Override
    public String problemBlock(RoundContext ctx) {
        if (ctx.questionSlug() == null) {
            return "";
        }
        BehavioralQuestion q = bank.get(ctx.questionSlug());
        StringBuilder sb = new StringBuilder();
        sb.append("QUESTION: ").append(q.getTitle()).append("\n\n");
        sb.append(q.getPrompt().strip()).append("\n\n");
        sb.append("Primary STAR element to press hardest on: ").append(q.getStarFocus()).append("\n\n");
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
                    You have just posed the question. Wait for the candidate to start their story.
                    Advance to STORY_SELECTION once they begin.
                    """;
            case STORY_SELECTION -> """
                    Let them pick and start telling their story with minimal interruption — this
                    phase is about which story they chose and how they frame it, not probing yet.
                    If they freeze on which story to tell, one nudge is fine ("any project comes
                    to mind — recent or not"), but do not suggest a specific story for them.
                    Advance to STAR_PROBE once they've stated the situation and are into the action.
                    """;
            case STAR_PROBE -> """
                    Now probe the gaps: their specific individual action (not the team's), the
                    actual result, and enough concrete detail that you could not have guessed the
                    story from the question alone. This is where specificity, ownership and
                    impact_and_result mostly get their signal — push at least once on any of the
                    three if the first pass was vague.
                    Advance to REFLECTION once the core story is fully drawn out.
                    """;
            case REFLECTION -> """
                    Ask what they'd do differently now, or what they learned. A credible,
                    specific answer here is where self_awareness gets scored — a generic "I
                    learned communication is important" is weak; push once for something more
                    specific if that's what you get.
                    Advance to WRAP once you have a genuine answer, not just a platitude.
                    """;
            case WRAP -> """
                    Thank the candidate warmly — this round has no "gotcha" ending — and call
                    end_round. No coaching, no feedback on their answer.
                    """;
            default -> "Unexpected phase for a behavioral round: " + ctx.phase()
                    + ". Proceed cautiously and prefer WRAP.";
        };

        return header + directive;
    }

    @Override
    public String openingBrief(RoundContext ctx) {
        if (ctx.questionSlug() == null) {
            return "Let's get started.";
        }
        BehavioralQuestion q = bank.get(ctx.questionSlug());
        return "Let's get started. " + q.getPrompt().strip()
                + "\n\nTake your time — walk me through it.";
    }

    @Override
    public ArtifactKind artifactKind() {
        return ArtifactKind.SCRATCH;
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
