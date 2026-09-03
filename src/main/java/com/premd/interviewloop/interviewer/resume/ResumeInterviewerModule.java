package com.premd.interviewloop.interviewer.resume;

import com.premd.interviewloop.domain.Resume;
import com.premd.interviewloop.domain.enums.ArtifactKind;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.interviewer.InterviewerModule;
import com.premd.interviewloop.interviewer.QuestionSelection;
import com.premd.interviewloop.interviewer.RoundContext;
import com.premd.interviewloop.resume.ResumeService;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Resume deep-dive interviewer module — practice-only, not part of any company's formal
 * loop (see {@link ModuleType#RESUME}).
 *
 * <p>Phase sequence: BRIEFING → PROJECT_SELECTION → ROLE_AND_CONTRIBUTION →
 * TECHNICAL_DEEP_DIVE → IMPACT_AND_METRICS → WRAP.
 *
 * <p>Unlike every other module, this one has no file-backed question bank — the
 * "content" is the candidate's own uploaded resume ({@code resume.ResumeService}). The
 * model itself picks which project or experience to focus on, the way a real interviewer
 * scanning a resume would, rather than the backend choosing a fixed target. The resume
 * text is pinned by content hash at round start ({@link #selectQuestion}) so a later
 * re-upload mid-round cannot change what this round is being scored against.
 *
 * <p><b>The candidate's resume is real personal data, sent to whichever LLM provider is
 * conducting this round.</b> That is inherent to the feature — uploading a resume is an
 * explicit, deliberate action — but it is worth stating plainly rather than leaving
 * implicit. See {@code resume.ResumeService} for what is and isn't persisted.
 */
@Component
public class ResumeInterviewerModule implements InterviewerModule {

    private static final String RUBRIC_VERSION = "resume-v1";

    private static final String RUBRIC = """
            RESUME DEEP-DIVE ROUND RUBRIC (%s)

            Score the candidate on these five dimensions, 1-5 each, calibrated to an SDE-2 bar
            (roughly two years of experience) — not senior, not new-grad.
              1 = well below the bar    3 = at the bar    5 = clearly above it

            - ownership_clarity      — can they clearly separate what THEY specifically did from
                                       what their team or others did? A resume bullet phrased as
                                       "built X" that turns out to be "was one of six engineers
                                       on X, my part was Y" is normal — the question is whether
                                       they clarify this themselves or need it dragged out.
            - technical_depth        — do they know the actual technical details of what they
                                       built — real component names, real trade-offs — or does it
                                       stay at the level of the resume bullet's own marketing
                                       language?
            - decision_reasoning     — can they explain WHY they made specific technical choices
                                       on this project, with real alternatives they considered, or
                                       only WHAT they built?
            - impact_articulation    — can they concretely describe or quantify the outcome, or
                                       does it stay vague ("it improved performance")?
            - consistency_with_resume — does what they say in conversation match and coherently
                                       expand on what's written, or does it contradict or seem
                                       inflated relative to the resume's own claims?

            Use exactly these five strings as the `dimension` argument to record_signal — no
            synonyms, no new dimensions. Record a signal as soon as you observe it; do not wait
            for the round to end.
            """.formatted(RUBRIC_VERSION);

    private final ResumeService resumeService;

    public ResumeInterviewerModule(ResumeService resumeService) {
        this.resumeService = resumeService;
    }

    @Override
    public ModuleType moduleType() {
        return ModuleType.RESUME;
    }

    @Override
    public QuestionSelection selectQuestion(RoundContext ctx) {
        Resume resume = resumeService.requireCurrent();
        String slug = "resume-" + resume.getContentHash().substring(0, 12);
        return new QuestionSelection(slug, resume.getContentText(), resume.getContentHash(),
                ctx.difficultyTarget());
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
        sb.append("You are conducting a resume deep-dive interview round");
        if (ctx.companyDisplayName() != null) {
            sb.append(" at ").append(ctx.companyDisplayName());
        }
        sb.append(".\n\n");

        sb.append(
                """
                The candidate is interviewing for an SDE-2 backend role — roughly two years of
                professional experience. You have their actual resume below. Your job is to pick
                ONE project or experience from it and go deep — real interviewers spend most of a
                resume-review round on one or two items, not a shallow pass over everything listed.

                Run it like a real interviewer who has read the resume closely, not skimmed it:
                - Pick something specific and ask about it by name — quote the actual bullet or
                  project name from the resume, not a generic "tell me about your experience."
                - Separate the resume's language from theirs. A resume bullet is marketing; make
                  them re-describe it in their own words, then compare.
                - Push past the resume bullet into what they'd only know if they actually did the
                  work: the real technical shape, a decision they made and why, a number.
                - If something they say seems inconsistent with what's on the resume, ask about it
                  directly and neutrally — "the resume says X, you're describing Y — help me
                  reconcile that" — do not accuse, just probe.
                - Ask, don't tell. If they're stuck being specific, use set_hint_level to nudge
                  toward the kind of detail you want, not to supply it.
                - Keep your own turns short. This round is about drawing out THEIR specifics.

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

        if (ctx.carryOverBrief() != null && !ctx.carryOverBrief().isBlank()) {
            sb.append("\nPrivate panel handoff — do not mention this note to the candidate: ")
                    .append(ctx.carryOverBrief())
                    .append(" Assess this round on its own evidence; use the handoff only to choose useful probes.\n");
        }

        return sb.toString();
    }

    @Override
    public String problemBlock(RoundContext ctx) {
        if (ctx.questionContentHash() == null) {
            return "";
        }
        Resume resume = resumeService.byHash(ctx.questionContentHash());
        StringBuilder sb = new StringBuilder();
        sb.append("CANDIDATE'S RESUME");
        if (resume.getOriginalFilename() != null) {
            sb.append(" (").append(resume.getOriginalFilename()).append(")");
        }
        sb.append(":\n\n");
        sb.append(resume.getContentText().strip()).append("\n");
        return sb.toString();
    }

    @Override
    public String phaseDirective(RoundContext ctx) {
        String remaining = ctx.remainingSec() == null ? "untimed" : (ctx.remainingSec() / 60) + " min left";
        String hint = "hint level " + ctx.hintLevel() + "/3";
        String header = "Phase: " + ctx.phase() + " · " + remaining + " · " + hint + "\n\n";

        String directive = switch (ctx.phase()) {
            case BRIEFING -> """
                    You have just opened the round. Wait for the candidate to respond.
                    Advance to PROJECT_SELECTION once they engage.
                    """;
            case PROJECT_SELECTION -> """
                    Pick ONE specific project or experience from the resume above and name it
                    explicitly, quoting the resume's own wording for what it claims. Ask them to
                    walk you through it. Do not let them pick something not on the resume, and do
                    not ask about more than one item in this phase.
                    Advance to ROLE_AND_CONTRIBUTION once they've started describing it.
                    """;
            case ROLE_AND_CONTRIBUTION -> """
                    Establish exactly what THEY did versus their team. If the resume's phrasing is
                    ambiguous about scope ("built X"), ask directly: "was this you solo, or was
                    there a team — what was your specific piece?" This phase is where
                    ownership_clarity gets its signal — do not move on with this still unclear.
                    Advance to TECHNICAL_DEEP_DIVE once their individual contribution is clear.
                    """;
            case TECHNICAL_DEEP_DIVE -> """
                    Descend past the resume bullet's own language into real technical detail: how
                    it actually worked, and at least one specific decision they made and why —
                    what alternative did they consider and reject? This phase is where
                    technical_depth and decision_reasoning get most of their signal; push at
                    least once past a first answer that stays at resume-bullet altitude.
                    Advance to IMPACT_AND_METRICS once you have real mechanism-level detail.
                    """;
            case IMPACT_AND_METRICS -> """
                    Ask for the outcome, concretely — a number, a before/after, a consequence —
                    not just "it went well." If something they've said so far seems inconsistent
                    with the resume's own claims, this is also the phase to reconcile it directly
                    and neutrally, once.
                    Advance to WRAP once you have a real answer or it's clear none is coming.
                    """;
            case WRAP -> """
                    Thank the candidate, note anything you told them you'd flag, and call
                    end_round. No coaching, no revealing your assessment.
                    """;
            default -> "Unexpected phase for a resume round: " + ctx.phase()
                    + ". Proceed cautiously and prefer WRAP.";
        };

        return header + directive;
    }

    @Override
    public String openingBrief(RoundContext ctx) {
        return "Let's get started. I've got your resume in front of me — give me a second while "
                + "I pick something to dig into, then we'll go from there.";
    }

    @Override
    public ArtifactKind artifactKind() {
        return ArtifactKind.SCRATCH;
    }

    @Override
    public String artifactLabel() {
        return "Notes";
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
