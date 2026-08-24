package com.premd.interviewloop.content.java;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Loads the real {@code question-bank/java_deep_dive} directory — hand-editable data,
 * so fail-fast validation is part of the contract.
 */
class JavaScenarioBankTest {

    private JavaScenarioBank bank;

    @BeforeEach
    void setUp() {
        bank = new JavaScenarioBank();
        ReflectionTestUtils.setField(bank, "bankDir", "question-bank");
        bank.init();
    }

    @Test
    void loadsEveryScenarioWithDiagnosisAndNotes() {
        for (String slug : new String[]{
                "mutable-key-hashmap", "transaction-self-invocation", "singleton-state-race",
                "orders-endpoint-pool-exhaustion", "async-aggregation-starvation",
                "static-cache-oldgen-thrash"}) {
            JavaScenario s = bank.get(slug);
            assertThat(s.getTitle()).isNotBlank();
            assertThat(s.getScenarioText()).isNotBlank();
            assertThat(s.getExpectedDiagnosis()).isNotBlank();
            assertThat(s.getInterviewerNotes()).isNotBlank();
            assertThat(s.getProbes()).isNotEmpty();
            assertThat(s.getTradeOffQuestions()).isNotEmpty();
        }
    }

    @Test
    void unknownSlugThrows() {
        assertThatThrownBy(() -> bank.get("nope")).isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void contentHashTracksScenarioTextOnly() {
        String a = bank.contentHash("transaction-self-invocation");
        assertThat(a).isEqualTo(bank.contentHash("transaction-self-invocation"));
        // Hashes are per-slug: different scenarios hash differently.
        assertThat(a).isNotEqualTo(bank.contentHash("singleton-state-race"));
    }

    @Test
    void selectForHonoursExactDifficulty() {
        assertThat(bank.selectFor("medium").getDifficulty()).isEqualTo("medium");
        assertThat(bank.selectFor("medium-hard").getDifficulty()).isEqualTo("medium-hard");
    }

    @Test
    void selectForFallsBackWhenTargetMissing() {
        assertThat(bank.selectFor("easy").getDifficulty()).isIn("medium");
    }
}
