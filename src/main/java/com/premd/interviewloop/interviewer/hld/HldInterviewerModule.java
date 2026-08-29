package com.premd.interviewloop.interviewer.hld;

import com.premd.interviewloop.content.hld.HldQuestion;
import com.premd.interviewloop.content.hld.HldQuestionBank;
import com.premd.interviewloop.domain.enums.ArtifactKind;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.interviewer.InterviewerModule;
import com.premd.interviewloop.interviewer.QuestionSelection;
import com.premd.interviewloop.interviewer.RoundContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * HLD interviewer module — Phase 4.
 *
 * <p>Phase sequence (enforced by {@code SessionStateMachine}, not here):
 * BRIEFING → REQUIREMENTS → ESTIMATION → HIGH_LEVEL → DEEP_DIVE → BOTTLENECK → WRAP.
 *
 * <p>The candidate's work surface is a structured node/edge component graph (DM-2),
 * serialised to JSON and delivered as the volatile artifact each turn — the model reasons
 * over named components, so directives in HIGH_LEVEL and BOTTLENECK deliberately tell it
 * to probe components BY NAME from that graph rather than accepting an unnamed "a service
 * layer".
 *
 * <p>SDE-2 calibration note: high-level design at two years of experience means one
 * coherent system — sensible API sketch, datastore choice justified by access pattern, a
 * scaling move or two grounded in arithmetic, honest bottlenecks. It does NOT mean
 * multi-region global systems, exotic consistency protocols, or staff-level trade-off
 * surveys; a round pitched there produces no useful signal for this candidate.
 */
@Component
public class HldInterviewerModule implements InterviewerModule {

    private static final String RUBRIC_VERSION = "hld-v1";

    private static final String RUBRIC = """
            HLD ROUND RUBRIC (%s)

            Score the candidate on these six dimensions, 1-5 each, calibrated to an SDE-2 bar
            (roughly two years of experience) — not senior, not new-grad.
              1 = well below the bar    3 = at the bar    5 = clearly above it

            - requirements_scoping      — did they pin down functional scope, users, scale
                                          targets and what is explicitly out of scope before
                                          designing? Designing against invented requirements,
                                          or silently absorbing every feature into scope, both
                                          score poorly.
            - capacity_estimation       — do they turn the stated numbers (QPS, read/write
                                          ratio, object sizes) into storage, bandwidth and
                                          machine counts with visible arithmetic? Ballpark
                                          orders of magnitude are fine; hand-waving without
                                          numbers is not.
            - component_design          — is the system they draw actually coherent? Named
                                          components with clear responsibilities, a datastore
                                          choice tied to the ACCESS PATTERN, and data flows a
                                          reviewer could trace end to end.
            - trade_off_reasoning       — when choosing between two real options (SQL vs NoSQL,
                                          push vs pull, cache levels), do they state what each
                                          option costs and why THIS problem tips the balance?
                                          Naming one option's advantages only is half an answer.
            - bottleneck_identification — can they point at where THEIR design breaks first under
                                          the stated load and say what it would take to relieve
                                          it? A candidate who cannot critique their own diagram
                                          is reciting, not designing.
            - depth_on_probe            — when pushed one level down on a component ("what does
                                          that cache hold? when is it wrong?"), do they follow
                                          with plausible mechanism detail or deflect?

            Use exactly these six strings as the `dimension` argument to record_signal — no
            synonyms, no new dimensions. Record a signal as soon as you observe it; do not wait
            for the round to end.
            """.formatted(RUBRIC_VERSION);

    private final HldQuestionBank bank;

    public HldInterviewerModule(HldQuestionBank bank) {
        this.bank = bank;
    }

    @Override
    public ModuleType moduleType() {
        return ModuleType.HLD;
    }

    @Override
    public QuestionSelection selectQuestion(RoundContext ctx) {
        HldQuestion q = bank.selectFor(ctx.difficultyTarget());
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
        sb.append("You are conducting a High-Level Design (system design) interview round");
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
        sb.append("""

                 — roughly two years of professional experience. Calibrate to SDE-2 system
                design: one coherent service-level architecture, datastore choices justified by
                access patterns, scaling moves grounded in back-of-envelope arithmetic, honest
                bottlenecks. Do NOT drift to staff/principal territory (multi-region global
                deployments, exotic consensus, geo-sharding strategy) unless the candidate goes
                there themselves — a senior-scope round produces no signal for this person.

                Run the round like a real system-design interviewer:
                - Numbers are the spine of this round. When the candidate asserts scale ("we'd
                  cache heavily"), ask what that means quantitatively for THIS problem. Accept
                  order-of-magnitude correctness; reject vibes.
                - The candidate has a diagram canvas beside the chat. Treat their graph as the
                  source of truth for their design: probe its ACTUAL components by name ("your
                  ReadCache — what evicts, and what happens on a miss?"). If something they say
                  verbally never lands on the canvas, point that out once.
                - Push on trade-offs by naming the alternative: "you chose Redis over a local
                  cache — what does that cost you?" An answer with no downside acknowledged is
                  incomplete; ask once more before accepting it.
                - Ask, don't tell. Escalate help gradually via set_hint_level. Never redesign
                  their system for them, even when they're stuck — hint toward the missing
                  question instead.
                - Keep your own turns short. One probe per turn.

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
        HldQuestion q = bank.get(ctx.questionSlug());
        StringBuilder sb = new StringBuilder();
        sb.append("PROBLEM: ").append(q.getTitle()).append(" (difficulty: ").append(q.getDifficulty()).append(")\n\n");
        sb.append(q.getStatement().strip()).append("\n\n");
        if (q.getConstraints() != null && !q.getConstraints().isEmpty()) {
            sb.append("Constraints:\n");
            for (String c : q.getConstraints()) {
                sb.append("- ").append(c.strip()).append("\n");
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
                    Advance to REQUIREMENTS once they engage.
                    """;
            case REQUIREMENTS -> """
                    Scope before boxes. They should restate functional scope, name the primary
                    users/actors, pick explicit non-goals, and extract the scale targets hidden
                    in the problem statement. If they start drawing immediately, redirect once:
                    "before components — what are we building, and for how many?" Do not feed
                    them the numbers; make them find them in the statement.
                    Advance to ESTIMATION once scope and targets are on the table.
                    """;
            case ESTIMATION -> """
                    Arithmetic time. From the targets THEY extracted, expect back-of-envelope
                    numbers: requests/sec split into reads vs writes, storage growth per month,
                    bandwidth for the heavy objects, and roughly how many machines/cache memory
                    that implies. Watch the working, not just the result — order-of-magnitude
                    right with visible method beats a lucky exact number with none. If they
                    freeze, set_hint_level and suggest ONE quantity to start from (e.g. writes
                    per second).
                    Advance to HIGH_LEVEL once estimates exist, even imperfect ones.
                    """;
            case HIGH_LEVEL -> """
                    They should now build the actual system on the diagram canvas: named
                    components, arrows showing the main request/data flows, and a datastore
                    choice per persistence need. Probe the graph as it grows — by component
                    NAME: "what does X own?", "who calls Y and when?". A box with no
                    responsibility is not a design; keep asking until boxes earn their place.
                    Verbal-only architecture with an empty canvas is worth calling out once,
                    gently. Advance to DEEP_DIVE when a coherent end-to-end flow exists.
                    """;
            case DEEP_DIVE -> """
                    Pick the ONE most interesting component in THEIR diagram — usually the
                    datastore or the hot-path cache — and descend one mechanism level: what it
                    holds, its schema/key shape, what a miss or failure looks like, how it
                    behaves at the peak numbers from ESTIMATION. This phase feeds
                    component_design and depth_on_probe; keep going until they bottom out or
                    the mechanism is genuinely nailed. Use the interviewer notes' expected shape
                    to steer, but probe THEIR choices, not the canonical one.
                    Advance to BOTTLENECK after one deep thread.
                    """;
            case BOTTLENECK -> """
                    Turn the interview around: make THEM critique their own system. Where does
                    it break FIRST at 10x the stated load — a specific component in their
                    diagram, named, with the failure mode described? Then what single change
                    relieves it, and what does THAT cost? Vague answers ("add more servers",
                    "it would scale") get exactly one push-back: "which component, concretely?"
                    Record bottleneck_identification based on how honest and specific they are
                    about their own weaknesses.
                    Advance to WRAP once one bottleneck is properly run to ground.
                    """;
            case WRAP -> """
                    Thank the candidate, briefly note anything you told them you'd flag, and
                    call end_round. Do not introduce new material here.
                    """;
            default -> "Unexpected phase for an HLD round: " + ctx.phase()
                    + ". Proceed cautiously and prefer WRAP.";
        };

        return header + directive;
    }

    @Override
    public String openingBrief(RoundContext ctx) {
        if (ctx.questionSlug() == null) {
            return "Let's get started.";
        }
        HldQuestion q = bank.get(ctx.questionSlug());
        StringBuilder sb = new StringBuilder();
        sb.append("Let's get started. Today's system design problem: **").append(q.getTitle()).append("**\n\n");
        sb.append(q.getStatement().strip()).append("\n\n");
        if (q.getConstraints() != null && !q.getConstraints().isEmpty()) {
            sb.append("Constraints:\n");
            for (String c : q.getConstraints()) {
                sb.append("- ").append(c.strip()).append("\n");
            }
            sb.append("\n");
        }
        sb.append("There's a diagram canvas beside the chat — build your design there as we go. ")
          .append("Before drawing, though: anything you want to pin down about scope or scale?");
        return sb.toString();
    }

    @Override
    public ArtifactKind artifactKind() {
        return ArtifactKind.DIAGRAM;
    }

    @Override
    public String artifactLabel() {
        return "Current design graph (JSON — nodes with id/label/type, edges connecting them)";
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
