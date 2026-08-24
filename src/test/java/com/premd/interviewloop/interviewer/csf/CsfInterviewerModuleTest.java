package com.premd.interviewloop.interviewer.csf;

import com.premd.interviewloop.content.csf.CsfPackBank;
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

class CsfInterviewerModuleTest {

    private CsfInterviewerModule module;
    private RoundContext ctx;

    @BeforeEach
    void setUp() {
        var bank = new CsfPackBank();
        ReflectionTestUtils.setField(bank, "bankDir", "question-bank");
        bank.init();
        module = new CsfInterviewerModule(bank);

        QuestionSelection selection = module.selectQuestion(ctx(null));
        ctx = ctx(selection.slug());
    }

    private RoundContext ctx(String slug) {
        return RoundContext.builder()
                .moduleType(ModuleType.CS_FUNDAMENTALS)
                .companyProfileId("google")
                .companyDisplayName("Google")
                .difficultyTarget("medium")
                .focusTags(List.of("networking"))
                .plannedDurationSec(900)
                .questionSlug(slug)
                .phase(RoundPhase.RAPID_FIRE)
                .hintLevel(0)
                .elapsedSec(120)
                .build();
    }

    @Test
    void moduleIdentity() {
        assertThat(module.moduleType()).isEqualTo(ModuleType.CS_FUNDAMENTALS);
        assertThat(module.artifactKind()).isEqualTo(ArtifactKind.SCRATCH);
        assertThat(module.rubricVersion()).isEqualTo("csf-v1");
    }

    /**
     * The evaluator maps recorded signals to rubric dimensions by exact string — the
     * dimension names in the rubric text are API, not prose.
     */
    @Test
    void rubricNamesExactlyTheFourDimensions() {
        String rubric = module.rubric();
        assertThat(rubric)
                .contains("- breadth ")
                .contains("- depth_on_probe ")
                .contains("- precision_of_language ")
                .contains("- honesty_at_boundary ");
        // And nothing else looks like a dimension bullet.
        assertThat(rubric.split("\n- ", -1).length - 1).isEqualTo(4);
    }

    @Test
    void problemBlockRendersEveryTopicWithExpectedPointsAndProbes() {
        String block = module.problemBlock(ctx);
        assertThat(block).contains("RAPID-FIRE PACK:");
        assertThat(block).contains("== Topic: Networking & HTTP ==");
        assertThat(block).contains("Expected in a solid answer:");
        assertThat(block).contains("Depth probes");
        assertThat(block).doesNotContain("null");
    }

    @Test
    void phaseDirectiveCoversAllThreePhases() {
        for (RoundPhase phase : List.of(RoundPhase.BRIEFING, RoundPhase.RAPID_FIRE, RoundPhase.WRAP)) {
            RoundContext phased = RoundContext.builder()
                    .moduleType(ModuleType.CS_FUNDAMENTALS)
                    .difficultyTarget("medium")
                    .plannedDurationSec(900)
                    .questionSlug(ctx.questionSlug())
                    .phase(phase)
                    .elapsedSec(phase == RoundPhase.RAPID_FIRE ? 700 : 880)
                    .build();
            assertThat(module.phaseDirective(phased)).isNotBlank();
        }
    }

    @Test
    void rapidFirePacingChangesWithRemainingTime() {
        RoundContext early = RoundContext.builder()
                .moduleType(ModuleType.CS_FUNDAMENTALS).plannedDurationSec(1800)
                .questionSlug(ctx.questionSlug()).phase(RoundPhase.RAPID_FIRE).elapsedSec(60).build();
        RoundContext late = RoundContext.builder()
                .moduleType(ModuleType.CS_FUNDAMENTALS).plannedDurationSec(1800)
                .questionSlug(ctx.questionSlug()).phase(RoundPhase.RAPID_FIRE).elapsedSec(1600).build();

        assertThat(module.phaseDirective(early)).contains("cover breadth across ALL topics");
        assertThat(module.phaseDirective(late)).contains("Under 4 minutes");
    }

    @Test
    void openingBriefListsTopicsAndSetsExpectations() {
        String brief = module.openingBrief(ctx);
        assertThat(brief).contains("rapid-fire");
        assertThat(brief).contains("Networking & HTTP");
        assertThat(brief).contains("I don't know");
    }

    @Test
    void personaCarriesQuirksVerbatimAndNeverInRubric() {
        RoundContext quirked = RoundContext.builder()
                .moduleType(ModuleType.CS_FUNDAMENTALS)
                .companyDisplayName("Atlassian")
                .difficultyTarget("medium")
                .quirkBehaviors(List.of("LEVEL NOTE: interviewer interrupts after 30 seconds."))
                .plannedDurationSec(900)
                .questionSlug(ctx.questionSlug())
                .phase(RoundPhase.RAPID_FIRE)
                .build();

        assertThat(module.persona(quirked))
                .contains("LEVEL NOTE: interviewer interrupts after 30 seconds.");
        assertThat(module.rubric()).doesNotContain("Atlassian").doesNotContain("interrupts");
    }
}
