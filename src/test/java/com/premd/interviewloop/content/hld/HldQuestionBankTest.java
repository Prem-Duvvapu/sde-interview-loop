package com.premd.interviewloop.content.hld;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Loads the real {@code question-bank/hld} directory — hand-editable data, so fail-fast
 * validation is part of the contract.
 */
class HldQuestionBankTest {

    private HldQuestionBank bank;

    @BeforeEach
    void setUp() {
        bank = new HldQuestionBank();
        ReflectionTestUtils.setField(bank, "bankDir", "question-bank");
        bank.init();
    }

    @Test
    void loadsEveryQuestionWithNotesAndConstraints() {
        for (String slug : new String[]{
                "url-shortener", "trending-topics", "group-chat", "news-feed"}) {
            HldQuestion q = bank.get(slug);
            assertThat(q.getTitle()).isNotBlank();
            assertThat(q.getStatement()).isNotBlank();
            assertThat(q.getInterviewerNotes()).isNotBlank();
            assertThat(q.getTags()).isNotEmpty();
        }
    }

    @Test
    void difficultySpreadCoversMediumThroughMediumHard() {
        // The plan's bar: HLD rounds peak at medium-hard (§0) — no 'hard' by design.
        assertThat(bank.selectFor("medium").getDifficulty()).isEqualTo("medium");
        assertThat(bank.selectFor("medium-hard").getDifficulty()).isEqualTo("medium-hard");
        assertThat(bank.selectFor("hard").getDifficulty()).isIn("medium", "medium-hard");
    }

    @Test
    void unknownSlugThrows() {
        assertThatThrownBy(() -> bank.get("nope")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> bank.contentHash("nope")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void contentHashIsStable() {
        assertThat(bank.contentHash("url-shortener"))
                .isEqualTo(bank.contentHash("url-shortener"))
                .hasSize(64);
    }

    @Test
    void blankDifficultyDefaultsToMedium() {
        assertThat(bank.selectFor(null).getDifficulty()).isEqualTo("medium");
    }
}
