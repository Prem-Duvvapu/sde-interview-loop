package com.premd.interviewloop.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Difficulty target for a round, as specified in company profiles.
 */
public enum DifficultyTarget {
    EASY("easy"),
    MEDIUM("medium"),
    MEDIUM_HARD("medium-hard"),
    HARD("hard");

    private final String value;

    DifficultyTarget(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static DifficultyTarget fromValue(String value) {
        for (DifficultyTarget d : values()) {
            if (d.value.equals(value)) {
                return d;
            }
        }
        throw new IllegalArgumentException("Unknown difficulty: " + value);
    }
}
