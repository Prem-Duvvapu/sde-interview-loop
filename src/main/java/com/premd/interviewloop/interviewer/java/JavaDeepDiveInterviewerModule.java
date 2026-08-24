package com.premd.interviewloop.interviewer.java;

import com.premd.interviewloop.content.java.JavaScenario;
import com.premd.interviewloop.content.java.JavaScenarioBank;
import com.premd.interviewloop.domain.enums.ArtifactKind;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.interviewer.InterviewerModule;
import com.premd.interviewloop.interviewer.QuestionSelection;
import com.premd.interviewloop.interviewer.RoundContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Java deep-dive interviewer module — Phase 5 (with {@link CsfInterviewerModule}).
 *
 * <p>Phase sequence: BRIEFING → SCENARIO → PROBE → DEPTH_LADDER → TRADE_OFF → WRAP.
 * One production-failure scenario per round; the round's job is to descend from "what
 * is happening" through mechanism-level understanding to what the candidate would trade
 * off to fix it. The depth ladder itself lives in the scenario file, not in backend state —
 * like CSF, the walk is model-driven inside a fixed stable prefix.
 */
@Component
public class JavaDeepDiveInterviewerModule implements InterviewerModule {

    private static final String RUBRIC_VERSION = "java-v1";

    private static final String RUBRIC = """
            JAVA DEEP-DIVE ROUND RUBRIC (%s)

            Score the candidate on these five dimensions, 1-5 each, calibrated to an SDE-2 bar
            (roughly two years of experience) — someone who has shipped and debugged real Spring
            services, not someone who has only read about them.
              1 = well below the bar    3 = at the bar    5 = clearly above it

            - api_fluency            — do they know the actual Java/Spring APIs involved, by name
                                       and signature, or only vague shapes ("some map thing")?
                                       Correct, specific API recall under mild pressure is the bar.
            - internals_depth        — when pushed below the surface annotation/API into the
                                       mechanism underneath (proxies, memory regions, thread states,
                                       SQL generated), can they follow with plausible detail?
            - concurrency_correctness — where the scenario involves concurrent access: do they
                                       identify the actual race/visibility problem and reach for a
                                       fix that addresses THAT problem, not a keyword ritual?
            - framework_trade_offs   — when asked to weigh alternatives (libraries, scopes, pools,
                                       styles), do they produce a genuine trade-off with costs on
                                       both sides, or marketing for their favourite?
            - scenario_diagnosis     — the core skill this round exists to test: given symptoms,
                                       do they form hypotheses, order them sensibly, say what they'd
                                       inspect to confirm BEFORE changing code, and land the right
                                       root cause?

            Use exactly these five strings as the `dimension` argument to record_signal — no
            synonyms, no new dimensions. Record a signal as soon as you observe it; do not wait
            for the round to end.
            """.formatted(RUBRIC_VERSION);

    private final JavaScenarioBank bank;

    public JavaDeepDiveInterviewerModule(JavaScenarioBank bank) {
        this.bank = bank;
    }

    @Override
    public ModuleType moduleType() {
        return ModuleType.JAVA_DEEP_DIVE;
    }

    @Override
    public QuestionSelection selectQuestion(RoundContext ctx) {
        JavaScenario s = bank.selectFor(ctx.difficultyTarget());
        return new QuestionSelection(s.getSlug(), s.getScenarioText(),
                bank.contentHash(s.getSlug()), s.getDifficulty());
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
        sb.append("You are conducting a Java & Spring deep-dive interview round");
        if (ctx.companyDisplayName() != null) {
            sb.append(" at ").append(ctx.companyDisplayName());
        }
        sb.append(".\n\n");

        sb.append(
                """
                The candidate is interviewing for an SDE-2 backend role — roughly two years of
                professional experience, typically with real production incidents behind them.
                This round hands them ONE production failure story and descends into it: diagnosis
                first, then the mechanism underneath, then what they would trade off to fix it.

                Run it like a senior-but-fair incident reviewer, not a quiz machine:
                - Diagnosis discipline matters more than speed. If they propose a fix before a
                  root cause, pull them back: "what would you check first to confirm that?"
                - Accept partial diagnoses that are genuinely correct for part of the story, then
                  push on the remainder. Most scenarios here hide more than one defect on purpose.
                - Push for mechanisms, not vocabulary. "Spring does proxying" is air until they
                  can say which calls cross the proxy boundary and which don't.
                - Never reveal the expected diagnosis from your notes. Hint escalation via
                  set_hint_level instead — and even then, hint toward an inspection step, not the
                  answer ("what would the pool metrics show?").
                - If they draw code in the editor to explain, engage with what they actually
                  wrote — wrong line-by-line details are worth more probing than right ones.
                - Keep your own turns short. Ask, react, record, next.

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
        JavaScenario s = bank.get(ctx.questionSlug());
        StringBuilder sb = new StringBuilder();
        sb.append("SCENARIO: ").append(s.getTitle())
          .append(" (difficulty: ").append(s.getDifficulty()).append(")\n\n");
        sb.append(s.getScenarioText().strip()).append("\n\n");

        if (s.getConstraints() != null && !s.getConstraints().isEmpty()) {
            sb.append("Constraints for the discussion:\n");
            for (String c : s.getConstraints()) {
                sb.append("- ").append(c.strip()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("--- Interviewer-only material (never reveal verbatim; use to steer probes) ---\n");
        sb.append("Expected diagnosis:\n").append(s.getExpectedDiagnosis().strip()).append("\n\n");

        if (s.getProbes() != null && !s.getProbes().isEmpty()) {
            sb.append("Depth-ladder probes (roughly ordered; use one per turn where the answer earns it):\n");
            for (String p : s.getProbes()) {
                sb.append("- ").append(p.strip().replaceAll("\\s+", " ")).append("\n");
            }
            sb.append("\n");
        }
        if (s.getTradeOffQuestions() != null && !s.getTradeOffQuestions().isEmpty()) {
            sb.append("Trade-off questions (for the TRADE_OFF phase):\n");
            for (String t : s.getTradeOffQuestions()) {
                sb.append("- ").append(t.strip().replaceAll("\\s+", " ")).append("\n");
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
                    You have just set up the scenario. Wait for the candidate to respond.
                    Advance to SCENARIO once they engage with the problem.
                    """;
            case SCENARIO -> """
                    Let them read and restate the problem in their own words. Draw out what they
                    notice: which symptoms they consider load-bearing, what they'd look at first.
                    Do NOT accept fixes yet — if they jump ahead, redirect once: "hold that thought,
                    what's your read of the cause?" Advance to PROBE when they've committed to a
                    first hypothesis out loud.
                    """;
            case PROBE -> """
                    Interrogate the hypothesis, not the person. Make them say what evidence would
                    confirm or kill it — logs, metrics, heap dumps, pool stats, whatever applies —
                    BEFORE any code change. If the first hypothesis is wrong, that is fine and
                    normal: help them notice WHY it can't be right from the evidence in the story.
                    Advance to DEPTH_LADDER once the root cause (or a solid partial one) is on the
                    table.
                    """;
            case DEPTH_LADDER -> """
                    Now descend. Take the confirmed root cause and go one mechanism level down —
                    use the depth-ladder probes from your notes, one per turn, each earned by the
                    previous answer. This phase is where internals_depth and concurrency_correctness
                    get scored; keep pushing until either the candidate bottoms out or you run out
                    of ladder. Bottoming out honestly scores better than smooth hand-waving.
                    Advance to TRADE_OFF after two or three rungs or when they hit their limit.
                    """;
            case TRADE_OFF -> """
                    Shift from analysis to judgement: pose the trade-off questions from your notes.
                    A pass here names real costs on BOTH sides and lands on a recommendation tied
                    to THIS scenario's constraints, not generic best-practice recitation. Push once
                    on any answer that has no downside mentioned.
                    """;
            case WRAP -> """
                    Thank the candidate, note anything you told them you'd flag, and call
                    end_round. No coaching, no revealing the full expected diagnosis.
                    """;
            default -> "Unexpected phase for a Java deep-dive round: " + ctx.phase()
                    + ". Proceed cautiously and prefer WRAP.";
        };

        return header + directive;
    }

    @Override
    public String openingBrief(RoundContext ctx) {
        if (ctx.questionSlug() == null) {
            return "Let's get started.";
        }
        JavaScenario s = bank.get(ctx.questionSlug());
        return "Let's get started. I've got a production war story for you: **" + s.getTitle()
                + "**.\n\n" + s.getScenarioText().strip()
                + "\n\nTake it away — start with your read of the situation. "
                + "You can sketch in the editor beside us if it helps.";
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
