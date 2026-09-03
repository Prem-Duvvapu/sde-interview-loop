package com.premd.interviewloop.interviewer.behavioral;

import com.premd.interviewloop.content.behavioral.BehavioralQuestionBank;
import com.premd.interviewloop.domain.enums.ArtifactKind;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.domain.enums.RoundPhase;
import com.premd.interviewloop.interviewer.QuestionSelection;
import com.premd.interviewloop.interviewer.RoundContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BehavioralInterviewerModuleTest {

    private BehavioralInterviewerModule module;
    private RoundContext ctx;

    @BeforeEach
    void setUp() {
        var bank = new BehavioralQuestionBank();
        ReflectionTestUtils.setField(bank, "bankDir", "question-bank");
        bank.init();
        module = new BehavioralInterviewerModule(bank);

        QuestionSelection selection = module.selectQuestion(ctx(null));
        ctx = ctx(selection.slug());
    }

    private RoundContext ctx(String slug) {
        return RoundContext.builder()
                .moduleType(ModuleType.BEHAVIORAL)
                .companyProfileId("google")
                .companyDisplayName("Google")
                .difficultyTarget("medium")
                .plannedDurationSec(1800)
                .questionSlug(slug)
                .phase(RoundPhase.STORY_SELECTION)
                .hintLevel(0)
                .elapsedSec(300)
                .build();
    }

    @Test
    void moduleIdentity() {
        assertThat(module.moduleType()).isEqualTo(ModuleType.BEHAVIORAL);
        assertThat(module.artifactKind()).isEqualTo(ArtifactKind.SCRATCH);
        assertThat(module.artifactLanguage()).isNull();
        assertThat(module.rubricVersion()).isEqualTo("behavioral-v1");
    }

    /**
     * Dimension names are API for the signal pipeline — exact strings, exactly five.
     */
    @Test
    void rubricNamesExactlyTheFiveDimensions() {
        String rubric = module.rubric();
        assertThat(rubric)
                .contains("- specificity ")
                .contains("- ownership ")
                .contains("- self_awareness ")
                .contains("- impact_and_result ")
                .contains("- communication_structure ");
        assertThat(rubric.split("\n- ", -1).length - 1).isEqualTo(5);
    }

    @Test
    void phaseDirectiveCoversTheFullSequence() {
        for (RoundPhase phase : List.of(RoundPhase.BRIEFING, RoundPhase.STORY_SELECTION,
                RoundPhase.STAR_PROBE, RoundPhase.REFLECTION, RoundPhase.WRAP)) {
            RoundContext phased = RoundContext.builder()
                    .moduleType(ModuleType.BEHAVIORAL)
                    .difficultyTarget("medium")
                    .plannedDurationSec(1800)
                    .questionSlug(ctx.questionSlug())
                    .phase(phase)
                    .elapsedSec(300)
                    .build();
            assertThat(module.phaseDirective(phased)).isNotBlank();
        }
    }

    /** STAR_PROBE is where the "I" vs "we" ownership push has to actually live. */
    @Test
    void starProbeDirectivePushesOnIndividualContribution() {
        String directive = module.phaseDirective(ctxWithPhase(RoundPhase.STAR_PROBE))
                .replaceAll("\\s+", " ");
        assertThat(directive).contains("not the team");
    }

    @Test
    void openingBriefPresentsThePromptDirectly() {
        String brief = module.openingBrief(ctx);
        // The candidate should see the actual bank question, not a paraphrase of it.
        String expectedPrompt = bank().get(ctx.questionSlug()).getPrompt().strip();
        assertThat(brief).contains(expectedPrompt);
    }

    @Test
    void problemBlockHidesInterviewerNotesLabelledAsInternal() {
        String block = module.problemBlock(ctx);
        assertThat(block).contains("Interviewer-only notes");
        assertThat(block).contains(bank().get(ctx.questionSlug()).getInterviewerNotes().strip());
    }

    @Test
    void personaCarriesQuirksVerbatimAndStaysSde2() {
        RoundContext quirked = RoundContext.builder()
                .moduleType(ModuleType.BEHAVIORAL)
                .companyDisplayName("Atlassian")
                .difficultyTarget("medium")
                .quirkBehaviors(List.of("Interviewer probes explicitly for the Atlassian value 'Play, as a team'."))
                .plannedDurationSec(1800)
                .questionSlug(ctx.questionSlug())
                .phase(RoundPhase.STORY_SELECTION)
                .build();

        assertThat(module.persona(quirked))
                .contains("Interviewer probes explicitly for the Atlassian value 'Play, as a team'.")
                .contains("two years of");
        // Rubric is company-free: it must stay byte-stable across rounds for caching.
        assertThat(module.rubric()).doesNotContain("Atlassian");
    }

    /** Full-loop handoff: the note must reach the model but stay marked as candidate-invisible. */
    @Test
    void carryOverBriefIsIncludedButMarkedPrivate() {
        RoundContext withHandoff = RoundContext.builder()
                .moduleType(ModuleType.BEHAVIORAL)
                .difficultyTarget("medium")
                .plannedDurationSec(1800)
                .questionSlug(ctx.questionSlug())
                .phase(RoundPhase.STORY_SELECTION)
                .carryOverBrief("Candidate struggled with ownership framing in the DSA round.")
                .build();

        String persona = module.persona(withHandoff);
        assertThat(persona)
                .contains("Candidate struggled with ownership framing in the DSA round.")
                .containsIgnoringCase("do not mention this note to the candidate");
    }

    @Test
    void noCarryOverBriefProducesNoHandoffSection() {
        assertThat(module.persona(ctx)).doesNotContainIgnoringCase("panel handoff");
    }

    private RoundContext ctxWithPhase(RoundPhase phase) {
        return RoundContext.builder()
                .moduleType(ModuleType.BEHAVIORAL)
                .difficultyTarget("medium")
                .plannedDurationSec(1800)
                .questionSlug(ctx.questionSlug())
                .phase(phase)
                .elapsedSec(600)
                .build();
    }

    private BehavioralQuestionBank bank() {
        var bank = new BehavioralQuestionBank();
        ReflectionTestUtils.setField(bank, "bankDir", "question-bank");
        bank.init();
        return bank;
    }
}
