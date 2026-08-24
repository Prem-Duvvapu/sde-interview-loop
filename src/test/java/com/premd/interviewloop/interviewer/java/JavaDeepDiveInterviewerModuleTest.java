package com.premd.interviewloop.interviewer.java;

import com.premd.interviewloop.domain.enums.ArtifactKind;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.domain.enums.RoundPhase;
import com.premd.interviewloop.content.java.JavaScenarioBank;
import com.premd.interviewloop.interviewer.QuestionSelection;
import com.premd.interviewloop.interviewer.RoundContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JavaDeepDiveInterviewerModuleTest {

    private JavaDeepDiveInterviewerModule module;
    private RoundContext ctx;

    @BeforeEach
    void setUp() {
        var bank = new JavaScenarioBank();
        ReflectionTestUtils.setField(bank, "bankDir", "question-bank");
        bank.init();
        module = new JavaDeepDiveInterviewerModule(bank);

        QuestionSelection selection = module.selectQuestion(ctx(null));
        ctx = ctx(selection.slug());
    }

    private RoundContext ctx(String slug) {
        return RoundContext.builder()
                .moduleType(ModuleType.JAVA_DEEP_DIVE)
                .companyProfileId("microsoft")
                .companyDisplayName("Microsoft")
                .difficultyTarget("medium")
                .plannedDurationSec(2700)
                .questionSlug(slug)
                .phase(RoundPhase.PROBE)
                .hintLevel(0)
                .elapsedSec(600)
                .build();
    }

    @Test
    void moduleIdentity() {
        assertThat(module.moduleType()).isEqualTo(ModuleType.JAVA_DEEP_DIVE);
        assertThat(module.artifactKind()).isEqualTo(ArtifactKind.CODE);
        assertThat(module.artifactLanguage()).isEqualTo("java");
        assertThat(module.rubricVersion()).isEqualTo("java-v1");
    }

    /** Dimension names in the rubric text are API for the signal pipeline — exact strings. */
    @Test
    void rubricNamesExactlyTheFiveDimensions() {
        String rubric = module.rubric();
        assertThat(rubric)
                .contains("- api_fluency ")
                .contains("- internals_depth ")
                .contains("- concurrency_correctness ")
                .contains("- framework_trade_offs ")
                .contains("- scenario_diagnosis ");
        assertThat(rubric.split("\n- ", -1).length - 1).isEqualTo(5);
    }

    @Test
    void problemBlockContainsScenarioAndInterviewerOnlyMaterial() {
        String block = module.problemBlock(ctx);
        assertThat(block).contains("SCENARIO: ");
        assertThat(block).contains("Interviewer-only material");
        assertThat(block).contains("Expected diagnosis:");
        assertThat(block).contains("Trade-off questions");
    }

    @Test
    void phaseDirectiveCoversTheFullSequence() {
        for (RoundPhase phase : List.of(RoundPhase.BRIEFING, RoundPhase.SCENARIO, RoundPhase.PROBE,
                RoundPhase.DEPTH_LADDER, RoundPhase.TRADE_OFF, RoundPhase.WRAP)) {
            RoundContext phased = RoundContext.builder()
                    .moduleType(ModuleType.JAVA_DEEP_DIVE)
                    .difficultyTarget("medium")
                    .plannedDurationSec(2700)
                    .questionSlug(ctx.questionSlug())
                    .phase(phase)
                    .elapsedSec(300)
                    .build();
            assertThat(module.phaseDirective(phased)).isNotBlank();
        }
    }

    @Test
    void openingBriefPresentsScenarioTitleAndText() {
        String brief = module.openingBrief(ctx);
        assertThat(brief).contains(module.problemBlock(ctx).split("\n", 2)[0].replace("SCENARIO: ", "").split(" \\(")[0]);
        assertThat(brief).contains("start with your read");
    }

    @Test
    void personaCarriesQuirksVerbatim() {
        RoundContext quirked = RoundContext.builder()
                .moduleType(ModuleType.JAVA_DEEP_DIVE)
                .companyDisplayName("Walmart Global Tech")
                .difficultyTarget("medium")
                .quirkBehaviors(List.of("Interviewer opens every reply with a timestamp summary."))
                .questionSlug(ctx.questionSlug())
                .phase(RoundPhase.PROBE)
                .build();

        assertThat(module.persona(quirked))
                .contains("Interviewer opens every reply with a timestamp summary.");
        assertThat(module.rubric()).doesNotContain("Walmart");
    }

    @Test
    void selectQuestionHonoursDifficultyFallback() {
        RoundContext hard = RoundContext.builder()
                .moduleType(ModuleType.JAVA_DEEP_DIVE)
                .difficultyTarget("hard")
                .phase(RoundPhase.BRIEFING)
                .build();
        QuestionSelection selection = module.selectQuestion(hard);
        // No 'hard' scenarios yet — nearest difficulty wins, never an exception.
        assertThat(selection.difficulty()).isIn("medium", "medium-hard");
        assertThat(selection.contentHash()).hasSize(64);
    }
}
