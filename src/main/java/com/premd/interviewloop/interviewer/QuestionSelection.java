package com.premd.interviewloop.interviewer;

/**
 * A question chosen for a round.
 *
 * <p>{@code contentHash} is recorded on the round so a past session can be interpreted
 * against the exact question text that was used, even after the bank is edited.
 *
 * @param slug        stable identifier within the module's question bank
 * @param statement   the full problem statement shown to the candidate and cached in the prompt prefix
 * @param contentHash hash of {@code statement}, persisted on the round
 * @param difficulty  difficulty target this question was selected against
 */
public record QuestionSelection(String slug, String statement, String contentHash, String difficulty) {

    public QuestionSelection {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("Question slug is required");
        }
        if (statement == null || statement.isBlank()) {
            throw new IllegalArgumentException("Question statement is required");
        }
    }
}
