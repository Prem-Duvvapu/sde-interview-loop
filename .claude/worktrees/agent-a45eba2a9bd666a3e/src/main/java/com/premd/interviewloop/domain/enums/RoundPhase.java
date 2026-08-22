package com.premd.interviewloop.domain.enums;

/**
 * Phase within a round — each module type defines its own phase sequence (§1.2).
 * This enum contains the union of all phases across all modules.
 */
public enum RoundPhase {
    // Common
    BRIEFING,
    WRAP,

    // DSA phases
    CLARIFYING,
    APPROACH,
    CODING,
    COMPLEXITY,
    EDGE_CASES,
    FOLLOW_UP,

    // LLD phases
    REQUIREMENTS,
    CLASS_MODEL,
    DEEP_DIVE,
    EXTENSION,

    // HLD phases (REQUIREMENTS and DEEP_DIVE shared with LLD)
    ESTIMATION,
    HIGH_LEVEL,
    BOTTLENECK,

    // CS Fundamentals — adaptive rapid-fire, not strictly phased
    RAPID_FIRE,

    // Java deep-dive phases
    SCENARIO,
    PROBE,
    DEPTH_LADDER,
    TRADE_OFF,

    // Meta
    PENDING
}
