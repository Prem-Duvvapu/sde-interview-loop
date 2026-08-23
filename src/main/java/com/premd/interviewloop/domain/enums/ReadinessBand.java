package com.premd.interviewloop.domain.enums;

/**
 * Readiness band — rubric score mapped to a hiring recommendation.
 * no-hire (<2.5), lean-hire (2.5–3.4), hire (3.5–4.2), strong-hire (>4.2)
 */
public enum ReadinessBand {
    NO_HIRE,
    LEAN_HIRE,
    HIRE,
    STRONG_HIRE;

    /**
     * Map a numeric score (1–5) to a readiness band.
     */
    public static ReadinessBand fromScore(double score) {
        if (score < 2.5) return NO_HIRE;
        if (score < 3.5) return LEAN_HIRE;
        if (score <= 4.2) return HIRE;
        return STRONG_HIRE;
    }

    /** Lowercase-hyphenated form used everywhere else in the API and data files. */
    public String wireValue() {
        return switch (this) {
            case NO_HIRE -> "no-hire";
            case LEAN_HIRE -> "lean-hire";
            case HIRE -> "hire";
            case STRONG_HIRE -> "strong-hire";
        };
    }
}
