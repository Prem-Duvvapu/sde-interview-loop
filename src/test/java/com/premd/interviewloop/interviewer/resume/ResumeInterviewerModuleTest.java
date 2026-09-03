package com.premd.interviewloop.interviewer.resume;

import com.premd.interviewloop.domain.Resume;
import com.premd.interviewloop.domain.enums.ArtifactKind;
import com.premd.interviewloop.domain.enums.ModuleType;
import com.premd.interviewloop.domain.enums.RoundPhase;
import com.premd.interviewloop.interviewer.QuestionSelection;
import com.premd.interviewloop.interviewer.RoundContext;
import com.premd.interviewloop.resume.ResumeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * No file-backed bank — {@link ResumeService} is mocked, since the module's whole
 * point is reading the candidate's live-uploaded resume rather than a fixed bank.
 */
class ResumeInterviewerModuleTest {

    private static final String RESUME_TEXT =
            "Jane Doe\nBackend Engineer\n\nBuilt a rate limiter for the API gateway "
                    + "using a token bucket algorithm, reducing overload incidents by 40%.";
    private static final String RESUME_HASH = "a".repeat(64);

    private ResumeService resumeService;
    private ResumeInterviewerModule module;
    private RoundContext ctx;

    @BeforeEach
    void setUp() {
        resumeService = mock(ResumeService.class);
        Resume resume = new Resume("jane-doe.pdf", RESUME_TEXT, RESUME_HASH);
        when(resumeService.requireCurrent()).thenReturn(resume);
        when(resumeService.byHash(RESUME_HASH)).thenReturn(resume);

        module = new ResumeInterviewerModule(resumeService);

        QuestionSelection selection = module.selectQuestion(baseCtx(null, null));
        ctx = baseCtx(selection.slug(), selection.contentHash());
    }

    private RoundContext baseCtx(String slug, String contentHash) {
        RoundContext.Builder builder = RoundContext.builder()
                .moduleType(ModuleType.RESUME)
                .companyProfileId("google")
                .companyDisplayName("Google")
                .difficultyTarget("medium")
                .plannedDurationSec(1800)
                .questionSlug(slug)
                .phase(RoundPhase.PROJECT_SELECTION)
                .hintLevel(0)
                .elapsedSec(300);
        if (contentHash != null) {
            builder.questionContentHash(contentHash);
        }
        return builder.build();
    }

    @Test
    void moduleIdentity() {
        assertThat(module.moduleType()).isEqualTo(ModuleType.RESUME);
        assertThat(module.artifactKind()).isEqualTo(ArtifactKind.SCRATCH);
        assertThat(module.artifactLabel()).isEqualTo("Notes");
        assertThat(module.rubricVersion()).isEqualTo("resume-v1");
    }

    /** Dimension names are API for the signal pipeline — exact strings, exactly five. */
    @Test
    void rubricNamesExactlyTheFiveDimensions() {
        String rubric = module.rubric();
        assertThat(rubric)
                .contains("- ownership_clarity ")
                .contains("- technical_depth ")
                .contains("- decision_reasoning ")
                .contains("- impact_articulation ")
                .contains("- consistency_with_resume ");
        assertThat(rubric.split("\n- ", -1).length - 1).isEqualTo(5);
    }

    @Test
    void selectQuestionPinsTheCurrentResumeContentHash() {
        QuestionSelection selection = module.selectQuestion(baseCtx(null, null));
        assertThat(selection.contentHash()).isEqualTo(RESUME_HASH);
        assertThat(selection.statement()).isEqualTo(RESUME_TEXT);
        assertThat(selection.slug()).startsWith("resume-");
    }

    @Test
    void selectQuestionFailsClearlyWhenNoResumeUploaded() {
        when(resumeService.requireCurrent())
                .thenThrow(new NoSuchElementException(
                        "No resume has been uploaded yet — upload one before starting a resume round."));

        assertThatThrownBy(() -> module.selectQuestion(baseCtx(null, null)))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("upload one before starting");
    }

    /**
     * The whole point of pinning by hash: a round must keep scoring against the resume it
     * started with even if a newer one is uploaded mid-round.
     */
    @Test
    void problemBlockReadsThePinnedVersionNotWhicheverIsCurrentNow() {
        // setUp() already called selectQuestion() once (that's how ctx got its pinned hash) —
        // this asserts problemBlock() re-resolves by that pinned hash rather than calling
        // requireCurrent()/current() again, which would read whatever is uploaded *now*.
        String block = module.problemBlock(ctx);
        assertThat(block).contains(RESUME_TEXT).contains("jane-doe.pdf");
        verify(resumeService).byHash(RESUME_HASH);
        verify(resumeService, times(1)).requireCurrent();
        verify(resumeService, never()).current();
    }

    @Test
    void problemBlockIsEmptyBeforeAQuestionIsPinned() {
        RoundContext unpinned = baseCtx(null, null);
        assertThat(module.problemBlock(unpinned)).isEmpty();
    }

    @Test
    void phaseDirectiveCoversTheFullSequence() {
        for (RoundPhase phase : List.of(RoundPhase.BRIEFING, RoundPhase.PROJECT_SELECTION,
                RoundPhase.ROLE_AND_CONTRIBUTION, RoundPhase.TECHNICAL_DEEP_DIVE,
                RoundPhase.IMPACT_AND_METRICS, RoundPhase.WRAP)) {
            RoundContext phased = baseCtx(ctx.questionSlug(), RESUME_HASH);
            RoundContext withPhase = RoundContext.builder()
                    .moduleType(ModuleType.RESUME)
                    .difficultyTarget("medium")
                    .plannedDurationSec(1800)
                    .questionSlug(phased.questionSlug())
                    .questionContentHash(RESUME_HASH)
                    .phase(phase)
                    .elapsedSec(300)
                    .build();
            assertThat(module.phaseDirective(withPhase)).isNotBlank();
        }
    }

    /** ROLE_AND_CONTRIBUTION exists specifically to force the "I" vs "the team" split. */
    @Test
    void roleAndContributionDirectivePushesOnIndividualScope() {
        RoundContext withPhase = RoundContext.builder()
                .moduleType(ModuleType.RESUME)
                .difficultyTarget("medium")
                .plannedDurationSec(1800)
                .questionSlug(ctx.questionSlug())
                .questionContentHash(RESUME_HASH)
                .phase(RoundPhase.ROLE_AND_CONTRIBUTION)
                .elapsedSec(300)
                .build();

        String directive = module.phaseDirective(withPhase).replaceAll("\\s+", " ");
        assertThat(directive).contains("ownership_clarity");
    }

    @Test
    void personaWarnsThatResumeContentIsRealAndCarriesQuirksVerbatim() {
        RoundContext quirked = RoundContext.builder()
                .moduleType(ModuleType.RESUME)
                .companyDisplayName("Google")
                .difficultyTarget("medium")
                .quirkBehaviors(List.of("Interviewer probes for concrete metrics on every claim."))
                .plannedDurationSec(1800)
                .questionSlug(ctx.questionSlug())
                .questionContentHash(RESUME_HASH)
                .phase(RoundPhase.PROJECT_SELECTION)
                .build();

        assertThat(module.persona(quirked))
                .contains("Interviewer probes for concrete metrics on every claim.")
                .contains("two years of");
        // Rubric is content-free: it must stay byte-stable across rounds for caching.
        assertThat(module.rubric()).doesNotContain("Jane Doe").doesNotContain("Google");
    }

    @Test
    void carryOverBriefIsIncludedButMarkedPrivate() {
        RoundContext withHandoff = RoundContext.builder()
                .moduleType(ModuleType.RESUME)
                .difficultyTarget("medium")
                .plannedDurationSec(1800)
                .questionSlug(ctx.questionSlug())
                .questionContentHash(RESUME_HASH)
                .phase(RoundPhase.PROJECT_SELECTION)
                .carryOverBrief("Candidate was vague about individual contribution in the LLD round.")
                .build();

        String persona = module.persona(withHandoff);
        assertThat(persona)
                .contains("Candidate was vague about individual contribution in the LLD round.")
                .containsIgnoringCase("do not mention this note to the candidate");
    }

    @Test
    void openingBriefDoesNotLeakResumeContentBeforeAProjectIsPicked() {
        // The candidate hasn't been shown resume content yet at BRIEFING — the model picks
        // what to bring up during PROJECT_SELECTION, not this static opener.
        assertThat(module.openingBrief(ctx)).doesNotContain("token bucket");
    }
}
