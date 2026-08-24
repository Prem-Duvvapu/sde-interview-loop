package com.premd.interviewloop.interviewer.csf;

import com.premd.interviewloop.content.csf.CsfPack;
import com.premd.interviewloop.content.csf.CsfPackBank;
import com.premd.interviewloop.domain.enums.ArtifactKind;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.domain.enums.RoundPhase;
import com.premd.interviewloop.interviewer.InterviewerModule;
import com.premd.interviewloop.interviewer.QuestionSelection;
import com.premd.interviewloop.interviewer.RoundContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * CS fundamentals interviewer module — Phase 5 (with {@link JavaDeepDiveInterviewerModule},
 * which shares this phase's depth-ladder machinery conceptually but not its content).
 *
 * <p>Phase sequence: BRIEFING → RAPID_FIRE → WRAP. The adaptive topic walk is NOT modelled
 * as backend phases — it happens inside RAPID_FIRE, driven by the model over the whole
 * pack, which is rendered once into the stable problem block. That is what keeps the
 * cached prefix intact for the entire round (§1.4): swapping questions mid-round from the
 * backend would invalidate caching on every turn, so instead the pack is fixed and the
 * walk order is the interviewer's volatile business.
 */
@Component
public class CsfInterviewerModule implements InterviewerModule {

    private static final String RUBRIC_VERSION = "csf-v1";

    private static final String RUBRIC = """
            CS FUNDAMENTALS ROUND RUBRIC (%s)

            Score the candidate on these four dimensions, 1-5 each, calibrated to an SDE-2 bar
            (roughly two years of experience) — not senior, not new-grad.
              1 = well below the bar    3 = at the bar    5 = clearly above it

            - breadth              — across the topics covered this round, how much genuine
                                     working knowledge do they have? Breadth means real recall
                                     under questioning, not recognition of buzzwords.
            - depth_on_probe       — when you take a correct answer one level deeper ("why does
                                     that work?"), can they follow, or does the knowledge stop at
                                     the surface? One solid level of depth beats three shallow
                                     topics.
            - precision_of_language — do they use technical terms correctly and specifically?
                                     "Basically it caches" is vague; "the page cache absorbs reads
                                     so the second query never touches disk" is precise. Penalise
                                     confident-sounding word salad exactly as much as silence.
            - honesty_at_boundary  — when they hit the edge of what they know, do they say so
                                     cleanly and reason from first principles, or bluff? A clean
                                     "I don't know, but my guess is X because Y" scores BETTER
                                     than a fluent wrong answer — score bluffs harshly.

            Use exactly these four strings as the `dimension` argument to record_signal — no
            synonyms, no new dimensions. Record a signal as soon as you observe it; do not wait
            for the round to end.
            """.formatted(RUBRIC_VERSION);

    private final CsfPackBank bank;

    public CsfInterviewerModule(CsfPackBank bank) {
        this.bank = bank;
    }

    @Override
    public ModuleType moduleType() {
        return ModuleType.CS_FUNDAMENTALS;
    }

    @Override
    public QuestionSelection selectQuestion(RoundContext ctx) {
        CsfPack pack = bank.selectFor(ctx.difficultyTarget(), ctx.focusTags());
        String statement = "Rapid-fire fundamentals round covering: "
                + pack.getTopics().stream().map(t -> t.getName()).reduce((a, b) -> a + "; " + b).orElse("")
                + ". Format: short questions, short answers, follow-up probes.";
        return new QuestionSelection(pack.getSlug(), statement,
                bank.contentHash(pack.getSlug()), pack.getDifficulty());
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
        sb.append("You are conducting a CS fundamentals rapid-fire interview round");
        if (ctx.companyDisplayName() != null) {
            sb.append(" at ").append(ctx.companyDisplayName());
        }
        sb.append(".\n\n");

        sb.append(
                """
                The candidate is interviewing for an SDE-2 backend role — roughly two years of
                professional experience. This round checks working breadth and honest depth, not
                encyclopaedic recall. Real interviewers at good companies run this round briskly
                and kindly; be that interviewer.

                Style rules, in priority order:
                - ONE question at a time. Never stack questions in a single turn; if they didn't
                  answer, repeat or simplify rather than adding more.
                - Keep your turns SHORT — one or two sentences. You are a probe, not a lecturer.
                - Move on quickly from a solid answer ("good — next:") and say nothing extra.
                  Lingering on answers they clearly know wastes the round's breadth budget.
                - When an answer is correct, take it EXACTLY one level deeper before moving on:
                  ask why it works, what breaks it, or where it fails at scale. That single probe
                  is where your depth_on_probe signal comes from.
                - When an answer smells like a bluff (fluent, vague, no mechanism), probe it
                  directly: "walk me through the mechanism" or "give me a concrete example".
                  A caught bluff is valuable signal — do not rescue them from it.
                - If they clearly don't know, accept it gracefully and move on. Never teach the
                  answer, not even a hint beyond set_hint_level escalation.
                - Do NOT announce scores, rubric dimensions, or that you are assessing a specific
                  dimension. Conduct the interview naturally.

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
        CsfPack pack = bank.get(ctx.questionSlug());
        StringBuilder sb = new StringBuilder();
        sb.append("RAPID-FIRE PACK: ").append(pack.getTitle())
          .append(" (difficulty: ").append(pack.getDifficulty()).append(")\n\n");

        for (var topic : pack.getTopics()) {
            sb.append("== Topic: ").append(topic.getName()).append(" ==\n");
            int n = 1;
            for (var q : topic.getQuestions()) {
                sb.append("Q").append(n++).append(". ").append(q.getPrompt().strip()).append("\n");
                if (q.getExpectedPoints() != null && !q.getExpectedPoints().isEmpty()) {
                    sb.append("   Expected in a solid answer:\n");
                    for (String p : q.getExpectedPoints()) {
                        sb.append("   - ").append(p.strip()).append("\n");
                    }
                }
                if (q.getProbes() != null && !q.getProbes().isEmpty()) {
                    sb.append("   Depth probes (use after a correct answer, one at a time):\n");
                    for (String p : q.getProbes()) {
                        sb.append("   - ").append(p.strip().replaceAll("\\s+", " ")).append("\n");
                    }
                }
                sb.append("\n");
            }
        }

        if (pack.getFocusHints() != null && !pack.getFocusHints().isEmpty()) {
            sb.append("--- Pack guidance ---\n");
            for (String hint : pack.getFocusHints()) {
                sb.append("- ").append(hint.strip()).append("\n");
            }
        }
        return sb.toString();
    }

    @Override
    public String phaseDirective(RoundContext ctx) {
        String remaining = ctx.remainingSec() == null ? "untimed" : (ctx.remainingSec() / 60) + " min left";
        String hint = "hint level " + ctx.hintLevel() + "/3";
        String header = "Phase: " + ctx.phase() + " · " + remaining + " · " + hint + "\n\n";

        String directive = switch (ctx.phase()) {
            case BRIEFING -> """
                    You have just explained the format. Wait for the candidate to respond.
                    Advance to RAPID_FIRE as soon as they engage.
                    """;
            case RAPID_FIRE -> rapidFireDirective(ctx);
            case WRAP -> """
                    The round is ending. Thank the candidate briefly — one or two sentences, no
                    feedback, no coaching — and call end_round.
                    """;
            default -> "Unexpected phase for a CS fundamentals round: " + ctx.phase()
                    + ". Proceed cautiously and prefer WRAP.";
        };

        return header + directive;
    }

    /**
     * The adaptive walk lives here because this is the one place that may change every turn.
     * Pacing scales with remaining time; everything else is standing instruction.
     */
    private String rapidFireDirective(RoundContext ctx) {
        Integer remaining = ctx.remainingSec();
        String pacing;
        if (remaining == null) {
            pacing = "Keep a steady pace.";
        } else if (remaining > 600) {
            pacing = "Plenty of time — cover breadth across ALL topics before deep probing.";
        } else if (remaining > 240) {
            pacing = "Past halfway — prioritise topics you haven't touched yet; skip further probes on already-covered ground.";
        } else {
            pacing = "Under 4 minutes — wrap the current question, record any last signals, and move toward WRAP.";
        }

        return """
                Conduct the rapid-fire walk through the pack above.

                Rules of the walk:
                - Work roughly in pack order, but ADAPT: if the candidate shows weakness in a
                  topic, one probing question there is worth more than completing the list.
                  If they show mastery, move on without exhausting every sub-question.
                - Aim to touch every topic in the pack; depth-first only where their answers
                  invite it.
                - After each answer, decide deliberately: probe deeper (strong or suspicious
                  answer), or move to the next question (adequate answer). Say which with a
                  natural transition, e.g. "good" / "let's park that one" / "next:".
                - record_signal as evidence accumulates — quote or closely paraphrase what the
                  candidate actually said in the evidence field. Vague evidence is weak signal.
                - %s

                Do not reveal this structure to the candidate; just conduct naturally.
                """.formatted(pacing);
    }

    @Override
    public String openingBrief(RoundContext ctx) {
        if (ctx.questionSlug() == null) {
            return "Let's get started.";
        }
        CsfPack pack = bank.get(ctx.questionSlug());
        String names = pack.getTopics().stream().map(t -> t.getName()).reduce((a, b) -> a + ", " + b).orElse("");
        return "Let's get started. This round is a rapid-fire walk through fundamentals — "
                + names + ". I'll ask short questions and expect short, precise answers; "
                + "when something you say is interesting I'll dig one level deeper. "
                + "Saying 'I don't know' is always better than guessing at me. Ready?";
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
