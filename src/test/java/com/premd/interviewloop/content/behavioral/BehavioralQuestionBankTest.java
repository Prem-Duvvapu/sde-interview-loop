package com.premd.interviewloop.content.behavioral;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Loads the real {@code question-bank/behavioral} directory — hand-editable data, so
 * fail-fast validation is part of the contract. Mirrors {@code HldQuestionBankTest}.
 */
class BehavioralQuestionBankTest {

    private BehavioralQuestionBank bank;

    @BeforeEach
    void setUp() {
        bank = new BehavioralQuestionBank();
        ReflectionTestUtils.setField(bank, "bankDir", "question-bank");
        bank.init();
    }

    @Test
    void loadsEveryQuestionWithPromptAndNotes() {
        for (String slug : new String[]{
                "technical-disagreement", "production-mistake", "ambiguous-requirements",
                "learning-under-pressure", "influence-without-authority"}) {
            BehavioralQuestion q = bank.get(slug);
            assertThat(q.getTitle()).isNotBlank();
            assertThat(q.getPrompt()).isNotBlank();
            assertThat(q.getInterviewerNotes()).isNotBlank();
            assertThat(q.getStarFocus()).isNotBlank();
            assertThat(q.getTags()).isNotEmpty();
        }
    }

    @Test
    void unknownSlugThrows() {
        assertThatThrownBy(() -> bank.get("nope")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> bank.contentHash("nope")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void contentHashIsStableAndFull() {
        assertThat(bank.contentHash("technical-disagreement"))
                .isEqualTo(bank.contentHash("technical-disagreement"))
                .hasSize(64);
    }

    @Test
    void blankDifficultyDefaultsToMedium() {
        assertThat(bank.selectFor(null).getDifficulty()).isEqualTo("medium");
    }

    @Test
    void selectForUnknownDifficultyFallsBackRatherThanThrowing() {
        // The bank has no 'easy' entries today — this must fall back, not blow up a round start.
        assertThat(bank.selectFor("easy")).isNotNull();
    }
}
