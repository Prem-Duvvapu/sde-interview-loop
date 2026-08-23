package com.premd.interviewloop.evaluation;

import java.util.List;
import java.util.Map;

/** Parsed, validated output of one {@code submit_evaluation} tool call. */
record EvaluationResult(
        Map<String, Integer> scores,
        List<String> strengths,
        List<String> gaps,
        String narrativeMd
) {}
