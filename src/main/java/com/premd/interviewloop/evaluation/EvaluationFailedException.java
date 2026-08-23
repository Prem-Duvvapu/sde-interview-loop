package com.premd.interviewloop.evaluation;

/**
 * The evaluator never returned a usable {@code submit_evaluation} call, even after a retry.
 * Callers should treat this as "no evaluation yet" — it must never block round completion,
 * which has already happened by the time evaluation runs.
 */
public class EvaluationFailedException extends RuntimeException {
    public EvaluationFailedException(String message) {
        super(message);
    }
}
