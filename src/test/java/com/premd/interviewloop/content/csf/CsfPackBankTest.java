package com.premd.interviewloop.content.csf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Loads the real {@code question-bank/cs_fundamentals} directory — these files are
 * hand-editable data, so the bank's fail-fast validation is part of the contract.
 */
class CsfPackBankTest {

    private CsfPackBank bank;

    @BeforeEach
    void setUp() {
        bank = new CsfPackBank();
        ReflectionTestUtils.setField(bank, "bankDir", "question-bank");
        bank.init();
    }

    @Test
    void loadsEveryPackWithTopics() {
        assertThat(bank.get("csf-core-systems-pack").getTopics()).isNotEmpty();
        assertThat(bank.get("csf-platform-java-pack").getTopics()).isNotEmpty();
        for (CsfTopic topic : bank.get("csf-core-systems-pack").getTopics()) {
            assertThat(topic.getQuestions()).isNotEmpty();
            assertThat(topic.getQuestions()).allSatisfy(q -> {
                assertThat(q.getPrompt()).isNotBlank();
                assertThat(q.getExpectedPoints()).isNotEmpty();
                assertThat(q.getProbes()).isNotEmpty();
            });
        }
    }

    @Test
    void unknownSlugThrows() {
        assertThatThrownBy(() -> bank.get("nope"))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> bank.contentHash("nope"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void contentHashIsStableAcrossCalls() {
        String a = bank.contentHash("csf-core-systems-pack");
        String b = bank.contentHash("csf-core-systems-pack");
        assertThat(a).isEqualTo(b).hasSize(64);
    }

    @Test
    void selectForReturnsRequestedDifficultyWhenAvailable() {
        CsfPack p = bank.selectFor("medium", List.of());
        assertThat(p.getDifficulty()).isEqualTo("medium");
    }

    @Test
    void selectForFallsBackToNearestDifficulty() {
        // No pack at 'hard' yet — must fall back rather than throw.
        CsfPack p = bank.selectFor("hard", List.of());
        assertThat(p.getDifficulty()).isIn("medium", "medium-hard");
    }

    @Test
    void focusTagsPreferCoveringPacks() {
        CsfPack p = bank.selectFor("medium", List.of("jvm"));
        assertThat(p.getSlug()).isEqualTo("csf-platform-java-pack");

        CsfPack q = bank.selectFor("medium", List.of("databases"));
        assertThat(q.getSlug()).isEqualTo("csf-core-systems-pack");
    }

    @Test
    void blankDifficultyDefaultsToMedium() {
        assertThat(bank.selectFor(null, List.of()).getDifficulty()).isEqualTo("medium");
        assertThat(bank.selectFor("  ", List.of()).getDifficulty()).isEqualTo("medium");
    }
}
