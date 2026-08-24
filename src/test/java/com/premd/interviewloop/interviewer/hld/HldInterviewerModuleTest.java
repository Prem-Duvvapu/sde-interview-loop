package com.premd.interviewloop.interviewer.hld;

import com.premd.interviewloop.content.hld.HldQuestionBank;
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

class HldInterviewerModuleTest {

    private HldInterviewerModule module;
    private RoundContext ctx;

    @BeforeEach
    void setUp() {
        var bank = new HldQuestionBank();
        ReflectionTestUtils.setField(bank, "bankDir", "question-bank");
        bank.init();
        module = new HldInterviewerModule(bank);

        QuestionSelection selection = module.selectQuestion(ctx(null));
        ctx = ctx(selection.slug());
    }

    private RoundContext ctx(String slug) {
        return RoundContext.builder()
                .moduleType(ModuleType.HLD)
                .companyProfileId("google")
                .companyDisplayName("Google")
                .difficultyTarget("medium")
                .plannedDurationSec(2700)
                .questionSlug(slug)
                .phase(RoundPhase.ESTIMATION)
                .hintLevel(0)
                .elapsedSec(600)
                .build();
    }

    @Test
    void moduleIdentity() {
        assertThat(module.moduleType()).isEqualTo(ModuleType.HLD);
        assertThat(module.artifactKind()).isEqualTo(ArtifactKind.DIAGRAM);
        assertThat(module.artifactLanguage()).isNull();
        assertThat(module.rubricVersion()).isEqualTo("hld-v1");
    }

    /**
     * Dimension names are API for the signal pipeline — exact strings, exactly six.
     */
    @Test
    void rubricNamesExactlyTheSixDimensions() {
        String rubric = module.rubric();
        assertThat(rubric)
                .contains("- requirements_scoping ")
                .contains("- capacity_estimation ")
                .contains("- component_design ")
                .contains("- trade_off_reasoning ")
                .contains("- bottleneck_identification ")
                .contains("- depth_on_probe ");
        assertThat(rubric.split("\n- ", -1).length - 1).isEqualTo(6);
    }

    /** The model must be told the artifact is a graph, or it will misread the JSON. */
    @Test
    void artifactLabelDescribesTheGraphFormat() {
        assertThat(module.artifactLabel()).contains("graph").containsIgnoringCase("json");
    }

    @Test
    void phaseDirectiveCoversTheFullSequence() {
        for (RoundPhase phase : List.of(RoundPhase.BRIEFING, RoundPhase.REQUIREMENTS,
                RoundPhase.ESTIMATION, RoundPhase.HIGH_LEVEL, RoundPhase.DEEP_DIVE,
                RoundPhase.BOTTLENECK, RoundPhase.WRAP)) {
            RoundContext phased = RoundContext.builder()
                    .moduleType(ModuleType.HLD)
                    .difficultyTarget("medium")
                    .plannedDurationSec(2700)
                    .questionSlug(ctx.questionSlug())
                    .phase(phase)
                    .elapsedSec(300)
                    .build();
            assertThat(module.phaseDirective(phased)).isNotBlank();
        }
    }

    /** HIGH_LEVEL and BOTTLENECK lean on DM-2: probe the candidate's actual named components. */
    @Test
    void directivesReferenceTheCandidatesDiagram() {
        // Text blocks wrap mid-phrase; compare on collapsed whitespace.
        String highLevel = module.phaseDirective(ctxWithPhase(RoundPhase.HIGH_LEVEL))
                .replaceAll("\\s+", " ");
        assertThat(highLevel).contains("diagram").contains("NAME");

        String bottleneck = module.phaseDirective(ctxWithPhase(RoundPhase.BOTTLENECK))
                .replaceAll("\\s+", " ");
        assertThat(bottleneck).contains("component in their diagram");
    }

    @Test
    void openingBriefPresentsProblemAndCanvas() {
        String brief = module.openingBrief(ctx);
        assertThat(brief).contains("**");
        assertThat(brief).contains("diagram canvas");
    }

    @Test
    void personaCarriesQuirksVerbatimAndStaysSde2() {
        RoundContext quirked = RoundContext.builder()
                .moduleType(ModuleType.HLD)
                .companyDisplayName("LinkedIn")
                .targetRoleTitle("SWE (non-senior)")
                .difficultyTarget("medium")
                .quirkBehaviors(List.of("Interviewer asks 'why not just use Kafka?' at least once."))
                .plannedDurationSec(2700)
                .questionSlug(ctx.questionSlug())
                .phase(RoundPhase.ESTIMATION)
                .build();

        assertThat(module.persona(quirked))
                .contains("Interviewer asks 'why not just use Kafka?' at least once.")
                .contains("two years of professional experience");
        // Rubric is company-free: it must stay byte-stable across rounds for caching.
        assertThat(module.rubric()).doesNotContain("LinkedIn").doesNotContain("Kafka");
    }

    private RoundContext ctxWithPhase(RoundPhase phase) {
        return RoundContext.builder()
                .moduleType(ModuleType.HLD)
                .difficultyTarget("medium")
                .plannedDurationSec(2700)
                .questionSlug(ctx.questionSlug())
                .phase(phase)
                .elapsedSec(900)
                .build();
    }
}
