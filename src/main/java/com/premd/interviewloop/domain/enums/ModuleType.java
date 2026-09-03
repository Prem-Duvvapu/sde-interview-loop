package com.premd.interviewloop.domain.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The interview module types, matching company profile round definitions.
 * RESUME is a practice-only module (§ AGENTS.md) — not part of any company's formal
 * loop, offered as a single-module round only.
 */
public enum ModuleType {
    DSA("dsa"),
    LLD("lld"),
    HLD("hld"),
    CS_FUNDAMENTALS("cs_fundamentals"),
    JAVA_DEEP_DIVE("java_deep_dive"),
    BEHAVIORAL("behavioral"),
    RESUME("resume");

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
