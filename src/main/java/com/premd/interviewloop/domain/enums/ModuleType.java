package com.premd.interviewloop.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The five interview module types, matching company profile round definitions.
 * Behavioral is included for profile completeness but is disabled in v1.
 */
public enum ModuleType {
    DSA("dsa"),
    LLD("lld"),
    HLD("hld"),
    CS_FUNDAMENTALS("cs_fundamentals"),
    JAVA_DEEP_DIVE("java_deep_dive"),
    BEHAVIORAL("behavioral");

    private final String value;

    ModuleType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static ModuleType fromValue(String value) {
        for (ModuleType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown module type: " + value);
    }
}
